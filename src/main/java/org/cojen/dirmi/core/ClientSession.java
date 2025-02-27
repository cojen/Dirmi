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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteException;

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
                  SocketAddress localAddr, SocketAddress remoteAddr)
    {
        super(engine, settings);

        mState = State.CONNECTED;

        // Start with a fake control connection in order for the addresses to be available to
        // the Connector.
        controlPipe(CorePipe.newNullPipe(localAddr, remoteAddr));
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

        StubInvoker root = factory.newStub(rootId, stubSupport());
        mStubs.put(root);
        mRoot = (R) root.init();

        StubInvoker.setRootOrigin(root);
    }

    /**
     * Reads any optional root Data fields from the pipe.
     */
    void readRootDataFields(CorePipe pipe) throws IOException {
        var root = ((Stub) mRoot).invoker();
        mStubFactoriesByClass.get(mRootType).readDataAndUnlatch(root, pipe);
    }

    @Override
    boolean close(int reason, CorePipe controlPipe) {
        if (mSettings.reconnectDelayMillis < 0 || mEngine.isClosed() || isClosed() ||
            isRootDisposed())
        {
            reason |= R_CLOSED;
        }

        if ((reason & R_CLOSED) != 0) {
            boolean justClosed = super.close(reason, null); // pass null to force close
            mEngine.removeSession(this);
            return justClosed;
        }

        reason |= R_DISCONNECTED;
        boolean justClosed = super.close(reason, controlPipe);

        if (!justClosed) {
            return false;
        }

        mEngine.reconnect(mSettings, mRootType, mRootName, remoteAddress(), this::reconnectAttempt);

        // Must compare to the expected state first, in case the reconnect was quick and the
        // state has already changed.
        casStateAndNotify(State.DISCONNECTED, State.RECONNECTING);

        return true;
    }

    private boolean isRootDisposed() {
        return mRoot instanceof Stub s
            && StubInvoker.cSupportHandle.getAcquire(s.invoker()) instanceof DisposedStubSupport;
    }

    @SuppressWarnings("unchecked")
    private boolean reconnectAttempt(Object result) {
        if (mEngine.isClosed() || isClosed()) {
            if (result instanceof ClientSession) {
                ((ClientSession) result).close(R_CLOSED, null);
            }
            close(R_CLOSED, null);
            return false;
        }

        if (result instanceof Throwable ex) {
            reconnectFailureNotify(ex);
            // Keep trying to reconnect unless the exception likely indicates an internal bug.
            return result instanceof IOException;
        }

        var newSession = (ClientSession<R>) result;

        newSession.initTypeCodeMap(newSession.mTypeCodeMap);

        moveConnectionsFrom(newSession);

        mEngine.changeIdentity(this, newSession.id);

        StubInvoker newRoot = ((Stub) newSession.mRoot).invoker();
        Object removed = newSession.mStubs.remove(newRoot);
        assert newRoot == removed;
        assert newSession.mStubs.size() == 0;

        mStubFactories.moveAll(newSession.mStubFactories);

        mStubFactoriesByClass.putAll(newSession.mStubFactoriesByClass);
        newSession.mStubFactoriesByClass.clear();

        CoreStubSupport newSupport = new CoreStubSupport(this);
        stubSupport(newSupport);

        cServerSessionIdHandle.setRelease(this, newSession.mServerSessionId);

        StubInvoker root = ((Stub) mRoot).invoker();
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
                if (stub.isRestorable()) {
                    Class<?> type = RemoteExaminer.remoteType(stub);
                    if (waitMap.add(type.getName())) {
                        try {
                            sendInfoRequest(type);
                        } catch (IOException e) {
                            throw CoreUtils.rethrow(e);
                        }
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

        // Copy any data fields into the existing root.
        copyData(newRoot, root);

        // For all restorable stubs (including the root), update the MethodIdWriter. For all
        // non-root restorable stubs, set a support object that allows them to restore on
        // demand. The root will have already been restored.

        Map<RemoteInfo, MethodIdWriter> writers = new HashMap<>();

        var restorableSupport = new RestorableStubSupport(newSupport);

        // Used to collect all the non-root restorable stubs.
        var restorableStubs = new ArrayList<StubInvoker>();

        mStubs.forEach(stub -> {
            if (!stub.isRestorable() && stub != mRoot) {
                return;
            }

            RemoteInfo original = RemoteInfo.examineStub(stub);
            MethodIdWriter writer = writers.get(original);

            Class<?> type = null;
            if (writer == null && !writers.containsKey(original)) {
                type = RemoteExaminer.remoteType(stub);
                RemoteInfo current = typeMap.get(type.getName());
                if (current == null) {
                    writer = MethodIdWriter.Unimplemented.THE;
                } else {
                    writer = MethodIdWriterMaker.writerFor(original, current, false);
                }
                writers.put(original, writer);
            }

            if (writer != null) {
                StubInvoker.cWriterHandle.setRelease(stub, writer);
            } else {
                // Although no remote methods changed, the current StubFactory is preferred.
                if (type == null) {
                    type = RemoteExaminer.remoteType(stub);
                }
                StubFactory factory = mStubFactoriesByClass.get(type);
                if (factory != null) {
                    StubInvoker.cWriterHandle.setRelease(stub, factory);
                }
            }

            StubSupport effectiveSupport;

            if (stub == mRoot) {
                effectiveSupport = newSupport;
            } else {
                effectiveSupport = restorableSupport;
                restorableStubs.add(stub);
            }

            StubInvoker.cSupportHandle.setRelease(stub, effectiveSupport);
        });

        unclose();

        // Attempt to eagerly restore the stubs, but they might be restored concurrently on
        // demand at this point.

        for (StubInvoker stub : restorableStubs) {
            try {
                restorableSupport.restore(stub, Throwable.class);
            } catch (Throwable e) {
                // Unable to eagerly restore, so leave it alone. The next remote call against
                // the stub will attempt to restore again, throwing an exception to the caller
                // if it fails.
            }
        }

        return false;
    }

    @Override
    public R root() {
        return mRoot;
    }

    @Override
    public void connected(SocketAddress localAddr, SocketAddress remoteAddr,
                          InputStream in, OutputStream out)
        throws IOException
    {
        var pipe = new CorePipe(localAddr, remoteAddr, in, out, CorePipe.M_CLIENT);
        pipe.initTypeCodeMap(mTypeCodeMap);

        long serverSessionId = (long) cServerSessionIdHandle.getAcquire(this);
        long cid = 0;

        if (serverSessionId != 0) {
            // Established a new connection for an existing session.

            int timeoutMillis = mSettings.pingTimeoutMillis;
            CloseTimeout timeoutTask = timeoutMillis < 0 ? null : new CloseTimeout(pipe);

            try {
                if (timeoutTask != null) {
                    mEngine.scheduleMillis(timeoutTask, timeoutMillis);
                }

                writeHeader(pipe, serverSessionId);
                pipe.flush();

                long version = pipe.readLong();
                if (version != CoreUtils.PROTOCOL_V2) {
                    throw new RemoteException("Unsupported protocol");
                }

                cid = pipe.readLong();

                if (cid == 0) {
                    throw new RemoteException("Unsupported protocol");
                }

                long sid = pipe.readLong();

                if (sid != serverSessionId) {
                    if (sid != 0) {
                        throw new RemoteException("Unsupported protocol");
                    }
                    // Connection was rejected.
                    Object message = pipe.readObject();
                    throw new RemoteException(String.valueOf(message));

                }

                CloseTimeout.cancelOrFail(timeoutTask);
            } catch (Throwable e) {
                if (timeoutTask != null) {
                    timeoutTask.cancel();
                }
                CoreUtils.closeQuietly(pipe);
                if (e instanceof RemoteException re) {
                    re.remoteAddress(remoteAddress());
                }
                throw e;
            }
        }

        registerNewAvailableConnection(pipe, cid);
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
                uncaught(e);
            }
        }
    }
}
