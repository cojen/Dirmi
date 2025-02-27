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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.SocketAddress;

import java.util.concurrent.locks.LockSupport;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class ServerSession<R> extends CoreSession<R> {
    static {
        // Reduce the risk of "lost unpark" due to classloading.
        // https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> clazz = LockSupport.class;
    }

    private final Skeleton<R> mRoot;

    private final long mReverseId = IdGenerator.nextPositive();

    // Queue of threads waiting for a reverse connection to be established.
    private ConnectWaiter mFirstWaiter, mLastWaiter;

    ServerSession(Engine engine, Settings settings, R root, CorePipe pipe) throws RemoteException {
        super(engine, settings);

        // Store the pipe before calling skeletonFor, in case the root is SessionAware. The
        // address fields should be available to it.
        registerNewConnection(pipe);
        controlPipe(pipe);

        // Define a special skeleton for accepting reverse connections.
        mSkeletons.put(new Connector(mReverseId));

        mRoot = mSkeletons.skeletonFor(root);
        mKnownTypes.put(new Item(mRoot.typeId()));
    }

    /**
     * Called after the session is created.
     */
    void writeHeader(Pipe pipe, long clientSessionId) throws IOException {
        pipe.writeLong(CoreUtils.PROTOCOL_V2);
        pipe.writeLong(clientSessionId);
        pipe.writeLong(id);
        pipe.writeLong(mRoot.id);
        pipe.writeLong(mRoot.typeId());
    }

    /**
     * Writes any optional root Data fields over the pipe, but doesn't flush.
     */
    void writeRootDataFields(Pipe pipe) throws IOException {
        mRoot.writeDataFields(pipe);
    }

    @Override
    boolean close(int reason, CorePipe controlPipe) {
        boolean justClosed = super.close(reason, null); // pass null to force close

        mEngine.removeSession(this);

        // Unpark all reverse connection waiters. They'll try to request a connection and fail
        // with an exception.

        ConnectWaiter waiter;
        conLockAcquire();
        try {
            waiter = mFirstWaiter;
            mFirstWaiter = null;
            mLastWaiter = null;
        } finally {
            conLockRelease();
        }

        while (waiter != null) {
            waiter.unpark();
            waiter = waiter.mNext;
        }

        return justClosed;
    }

    @Override
    public R root() {
        return mRoot.server();
    }

    @Override
    public void connected(SocketAddress localAddr, SocketAddress remoteAddr,
                          InputStream in, OutputStream out)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * @param pipe must have M_SERVER mode
     */
    void accepted(CorePipe pipe) throws IOException {
        if (mState == null) {
            // Accepted to a new session too soon. Wait for it to be ready.
            mStateLock.lock();
            mStateLock.unlock();
        }

        pipe.initTypeCodeMap(mTypeCodeMap);
        registerNewConnection(pipe);
        startRequestProcessor(pipe);
    }

    @Override
    void registerNewAvailableConnection(CorePipe pipe, long sessionId) throws RemoteException {
        super.registerNewAvailableConnection(pipe, sessionId);
        notifyConnectWaiter();
    }

    @Override
    boolean recycleConnection(CorePipe pipe) {
        if (super.recycleConnection(pipe)) {
            notifyConnectWaiter();
            return true;
        } else {
            return false;
        }
    }

    private void notifyConnectWaiter() {
        ConnectWaiter waiter;
        conLockAcquire();
        try {
            waiter = mFirstWaiter;
            if (waiter == null) {
                return;
            }
            ConnectWaiter next = waiter.mNext;
            mFirstWaiter = next;
            if (next == null) {
                mLastWaiter = null;
            }
        } finally {
            conLockRelease();
        }

        waiter.unpark();
    }

    @Override
    CorePipe connect() throws IOException {
        // Establish a reverse connection. Request that the client connect to this side (the
        // server) and start reading requests. The client can create a new connection or remove
        // from one from its connection pool. To designate the connection that was selected,
        // the client must first invoke the object that was provided by the request, passing no
        // method id or parameters. The special Connector skeleton is invoked on this side,
        // completing the reversal.

        ConnectWaiter waiter = null;

        while (true) {
            CorePipe pipe = tryObtainConnection();

            if (pipe != null) {
                return pipe;
            }

            mControlLock.lock();
            try {
                CorePipe controlPipe = controlPipe();
                controlPipe.write(C_REQUEST_CONNECTION);
                controlPipe.writeLong(mReverseId);
                controlPipe.flush();
            } finally {
                mControlLock.unlock();
            }

            if (waiter == null || waiter.mUnparked) {
                waiter = new ConnectWaiter(Thread.currentThread());

                conLockAcquire();
                try {
                    ConnectWaiter last = mLastWaiter;
                    if (last == null) {
                        mFirstWaiter = waiter;
                    } else {
                        last.mNext = waiter;
                    }
                    mLastWaiter = waiter;
                } finally {
                    conLockRelease();
                }
            }

            // Need to double check in case a connection became available immediately before
            // the enqueue operation. The notify might unpark this thread or another one.
            if (hasAvailableConnection()) {
                notifyConnectWaiter();
            }

            LockSupport.park();
        }
    }

    /**
     * Pseudo skeleton used for accepting reverse connections from the client.
     */
    private final class Connector extends Skeleton {
        Connector(long id) {
            super(id);
        }

        @Override
        public Class type() {
            return Connector.class;
        }

        @Override
        public long typeId() {
            return 0;
        }

        @Override
        public Object server() {
            return this;
        }

        @Override
        public Object invoke(Pipe pipe, Object context) {
            var cp = (CorePipe) pipe;
            cp.mMode = CorePipe.M_CLIENT;
            recycleConnection(cp);
            return Skeleton.STOP_READING;
        }
    }

    private static final class ConnectWaiter {
        final Thread mThread;
        ConnectWaiter mNext;
        volatile boolean mUnparked;

        ConnectWaiter(Thread thread) {
            mThread = thread;
        }

        void unpark() {
            mUnparked = true;
            LockSupport.unpark(mThread);
        }
    }
}
