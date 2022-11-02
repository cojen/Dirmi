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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.SocketAddress;

import java.util.HashMap;
import java.util.Map;

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
            throw CoreUtils.rethrow(e);
        }
    }

    private long mServerSessionId;
    private Class<R> mRootType;
    private byte[] mRootName;
    private RemoteInfo mServerRootInfo;
    private R mRoot;

    ClientSession(Engine engine, Settings settings,
                  SocketAddress localAddr, SocketAddress remoteAttr)
    {
        super(engine, settings);

        mState = State.CONNECTED;

        // Start with a fake control connection in order for the addresses to be available to
        // the Connector.
        controlPipe(CorePipe.newNullPipe(localAddr, remoteAttr));
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
        mServerRootInfo = rootInfo;

        StubFactory factory = StubMaker.factoryFor(rootType, rootTypeId, rootInfo);
        factory = mStubFactories.putIfAbsent(factory);

        mStubFactoriesByClass.putIfAbsent(rootType, factory);

        Stub root = factory.newStub(rootId, stubSupport());
        mStubs.put(root);
        mRoot = (R) root;

        Stub.setRootOrigin(root);
    }

    @Override
    void close(int reason, CorePipe controlPipe) {
        if (mSettings.reconnectDelayMillis < 0) {
            reason |= R_CLOSED;
        }

        if ((reason & R_CLOSED) != 0) {
            super.close(reason, null); // pass null to force close
            mEngine.removeSession(this);
            return;
        }

        reason |= R_DISCONNECTED;
        super.close(reason, controlPipe);

        mEngine.reconnect(mSettings, mRootType, mRootName, remoteAddress(), this::reconnectAttempt);

        // Must compare to the expected state first, in case the reconnect was quick and the
        // state has already changed.
        casStateAndNotify(State.DISCONNECTED, State.RECONNECTING);
    }

    @SuppressWarnings("unchecked")
    private boolean reconnectAttempt(Object result) {
        if (isClosed()) {
            if (result instanceof ClientSession) {
                ((ClientSession) result).close(R_CLOSED, null);
            }
            close(R_CLOSED, null);
            return false;
        }

        if (result == null) {
            // Keep trying to reconnect.
            return true;
        }

        if (!(result instanceof ClientSession)) {
            close(R_CLOSED, null);
            return false;
        }

        var newSession = (ClientSession<R>) result;

        newSession.initTypeCodeMap(newSession.mTypeCodeMap);

        moveConnectionsFrom(newSession);

        mEngine.changeIdentity(this, newSession.id);

        var newRoot = (Stub) newSession.mRoot;
        Object removed = newSession.mStubs.remove(newRoot);
        assert newRoot == removed;
        assert newSession.mStubs.size() == 0;

        mStubFactories.moveAll(newSession.mStubFactories);

        mStubFactoriesByClass.putAll(newSession.mStubFactoriesByClass);
        newSession.mStubFactoriesByClass.clear();

        CoreStubSupport newSupport = new CoreStubSupport(this);
        stubSupport(newSupport);

        cServerSessionIdHandle.setRelease(this, newSession.mServerSessionId);

        var root = (Stub) mRoot;
        mStubs.changeIdentity(root, newRoot.id);

        Map<String, RemoteInfo> typeMap;

        try {
            startTasks();

            RemoteInfo newServerRootInfo = newSession.mServerRootInfo;

            // Request server-side RemoteInfos for all restorable stubs. If any changes, then
            // the MethodIdWriters for the affected stubs need to be updated.

            var waitMap = new WaitMap<String, RemoteInfo>();
            mTypeWaitMap = waitMap;

            waitMap.put(RemoteExaminer.remoteType(root).getName(), newServerRootInfo);

            mStubs.forEach(stub -> {
                Class<?> type = RemoteExaminer.remoteType(stub);
                if (waitMap.add(type.getName())) {
                    try {
                        sendInfoRequest(type);
                    } catch (IOException e) {
                        throw CoreUtils.rethrow(e);
                    }
                }
            });

            sendInfoRequestTerminator();

            typeMap = waitMap.await();
            mTypeWaitMap = null;

            // Flush the control pipe to send any pending C_KNOWN_TYPE commands.
            flushControlPipe();
        } catch (IOException | InterruptedException e) {
            close(R_DISCONNECTED, null);
            return false;
        }

        // For all restorable stubs, update the MethodIdWriter and set a support object that
        // allows them to restore on demand.

        Map<RemoteInfo, MethodIdWriter> writers = new HashMap<>();

        var restorableSupport = new RestorableStubSupport(newSupport);

        mStubs.forEach(stub -> {
            if (!Stub.isRestorable(stub) && stub != mRoot) {
                return;
            }

            RemoteInfo original = RemoteInfo.examineStub(stub);
            MethodIdWriter writer = writers.get(original);

            Class<?> type = null;
            if (writer == null && !writers.containsKey(original)) {
                type = RemoteExaminer.remoteType(stub);
                RemoteInfo current = typeMap.get(type.getName());
                writer = MethodIdWriterMaker.writerFor(original, current, false);
                writers.put(original, writer);
            }

            if (writer != null) {
                Stub.cWriterHandle.setRelease(stub, writer);
            } else {
                // Although no remote methods changed, the current StubFactory is preferred.
                if (type == null) {
                    type = RemoteExaminer.remoteType(stub);
                }
                StubFactory factory = mStubFactoriesByClass.get(type);
                if (factory != null) {
                    Stub.cWriterHandle.setRelease(stub, factory);
                }
            }

            if (stub != mRoot) {
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
                          InputStream in, OutputStream out)
        throws IOException
    {
        var pipe = new CorePipe(localAddr, remoteAttr, in, out, CorePipe.M_CLIENT);
        pipe.initTypeCodeMap(mTypeCodeMap);

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
    void startTasks() throws IOException {
        // This field is only needed by a reconnect.
        mServerRootInfo = null;

        super.startTasks();
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
