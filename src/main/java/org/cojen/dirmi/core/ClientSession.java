/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.SocketAddress;

import org.cojen.dirmi.Pipe;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class ClientSession<R> extends CoreSession<R> {
    private static final VarHandle cServerSessionIdHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cServerSessionIdHandle = lookup.findVarHandle
                (ClientSession.class, "mServerSessionId", long.class);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    private long mServerSessionId;
    private Class<R> mRootType;
    private byte[] mRootName;
    private R mRoot;

    ClientSession(Engine engine, SocketAddress localAddr, SocketAddress remoteAttr) {
        super(engine);
        // Start with a fake control connection in order for the addresses to be available to
        // the Connector.
        setControlConnection(CorePipe.newNullPipe(localAddr, remoteAttr));
    }

    /**
     * Called after the session is created.
     */
    void writeHeader(Pipe pipe, long serverSessionId) throws IOException {
        pipe.writeLong(CoreUtils.PROTOCOL_V2);
        pipe.writeLong(id);
        pipe.writeLong(serverSessionId);
    }

    /**
     * Called to finish initialization of a new client session.
     *
     * @param rootInfo server-side root info
     */
    @SuppressWarnings("unchecked")
    void init(long serverId, Class<R> rootType, byte[] bname,
              long rootTypeId, RemoteInfo rootInfo, long rootId)
    {
        cServerSessionIdHandle.setRelease(this, serverId);
        mRootType = rootType;
        mRootName = bname;

        StubFactory factory = StubMaker.factoryFor(rootType, rootTypeId, rootInfo);
        factory = mStubFactories.putIfAbsent(factory);

        synchronized (mStubFactoriesByClass) {
            mStubFactoriesByClass.putIfAbsent(rootType, factory);
        }

        Stub root = factory.newStub(rootId, stubSupport());
        mStubs.put(root);
        mRoot = (R) root;
    }

    @Override
    void close(int reason) {
        if ((reason & CLOSED) != 0) {
            super.close(reason);
            mEngine.removeSession(this);
            return;
        }

        // FIXME: If reconnect is disabled, close as usual.

        reason |= DISCONNECTED;
        super.close(reason);

        // FIXME: Configurable reconnect delay.
        mEngine.reconnect(mRootType, mRootName, remoteAddress(), 1000, this::reconnectAttempt);
    }

    @SuppressWarnings("unchecked")
    private boolean reconnectAttempt(Object result) {
        if (isClosed()) {
            if (result instanceof ClientSession) {
                ((ClientSession) result).close(CLOSED);
            }
            close(CLOSED);
            return false;
        }

        if (result == null) {
            // Keep trying to reconnect.
            return true;
        }

        if (!(result instanceof ClientSession)) {
            close(CLOSED);
            return false;
        }

        var newSession = (ClientSession<R>) result;

        moveConnectionsFrom(newSession);

        mEngine.changeIdentity(this, newSession.id);

        var newRoot = (Stub) newSession.mRoot;
        Object removed = newSession.mStubs.remove(newRoot);
        assert newRoot == removed;
        assert newSession.mStubs.size() == 0;

        mStubFactories.moveAll(newSession.mStubFactories);

        mStubFactoriesByClass.putAll(newSession.mStubFactoriesByClass);
        newSession.mStubFactoriesByClass.clear();

        CoreStubSupport newSupport = newSession.stubSupport();
        stubSupport(newSupport);

        cServerSessionIdHandle.setRelease(this, newSession.mServerSessionId);

        var root = (Stub) mRoot;
        mStubs.changeIdentity(root, newRoot.id);

        try {
            mEngine.startSessionTasks(this);
        } catch (IOException e) {
            close(DISCONNECTED);
            return false;
        }

        /* FIXME: Fix MethodIdWriters for all stubs (including the root).

           Scan through the stubs and collect a set of RemoteInfos. To determine the RemoteInfo
           for a stub, access the interfaces to obtain the type. Pick the interface which isn't
           Remote, unless that's the only one.

           Using the control connection, request the server-side type ids and RemoteInfos.
           Update the stub factory maps as well. For any RemoteInfos that changed, update the
           affected Stubs. If the remote side no longer has the RemoteInfo, then the affected
           Stubs need a MethodIdWriter which always throws NoSuchMethodError. If no
           MethodIdWriter change is required, replace the Stub.miw field with the current
           StubFactory anyhow.

         */

        // Update the restorable stubs to restore on demand.
        var restorableSupport = new RestorableStubSupport(newSupport);

        mStubs.forEach(stub -> {
            var origin = (MethodHandle) Stub.cOriginHandle.getAcquire(stub);
            if (origin != null) {
                Stub.cSupportHandle.setRelease(stub, restorableSupport);
            }
        });

        unclose();

        return false;
    }

    @Override
    public R root() {
        return mRoot;
    }

    @Override
    public void connected(SocketAddress localAddr, SocketAddress remoteAttr,
                          InputStream in, OutputStream out) throws IOException
    {
        var pipe = new CorePipe(localAddr, remoteAttr, in, out, CorePipe.M_CLIENT);

        long serverSessionId = (long) cServerSessionIdHandle.getAcquire(this);

        if (serverSessionId != 0) {
            // Established a new connection for an existing session.
            try {
                writeHeader(pipe, serverSessionId);
                pipe.flush();
            } catch (IOException e) {
                CoreUtils.closeQuietly(pipe);
                throw e;
            }
        }

        registerNewAvailableConnection(pipe);
    }

    @Override
    CorePipe connect() throws IOException {
        while (true) {
            CorePipe pipe = tryObtainConnection();
            if (pipe != null) {
                return pipe;
            }
            mEngine.checkClosed().connect(this);
        }
    }

    @Override
    void reverseConnect(long id) {
        try {
            CorePipe pipe = connect();
            pipe.writeLong(id);
            pipe.flush();
            pipe.mMode = CorePipe.M_SERVER;
            recycleConnection(pipe);
        } catch (IOException e) {
            if (!isClosedOrDisconnected()) {
                uncaughtException(e);
            }
        }
    }
}
