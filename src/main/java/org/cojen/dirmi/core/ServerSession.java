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

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Pipe;

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

    private final long mReverseId = IdGenerator.next(IdGenerator.I_SERVER);

    // Queue of threads waiting for a reverse connection to be established.
    private ConnectWaiter mFirstWaiter, mLastWaiter;

    /**
     * @param rootInfo client-side root info
     */
    ServerSession(Engine engine, R root, RemoteInfo rootInfo) {
        super(engine, IdGenerator.I_SERVER);

        // FIXME: stash rootInfo in a string to info map

        mRoot = mSkeletons.skeletonFor(root);
        mKnownTypes.put(new Item(mRoot.typeId()));

        // For accepting reverse connections.
        mSkeletons.put(new Connector(mReverseId));
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

    @Override
    public void close() {
        super.close();

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
    }

    @Override
    public R root() {
        return mRoot.server();
    }

    @Override
    public void connected(SocketAddress localAddr, SocketAddress remoteAttr,
                          InputStream in, OutputStream out)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * @param pipe must have M_SERVER mode
     */
    void accepted(CorePipe pipe) throws IOException {
        registerNewConnection(pipe);
        startRequestProcessor(pipe);
    }

    @Override
    void registerNewAvailableConnection(CorePipe pipe) throws ClosedException {
        super.registerNewAvailableConnection(pipe);
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
                mControlPipe.write(C_REQUEST_CONNECTION);
                mControlPipe.writeLong(mReverseId);
                mControlPipe.flush();
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
        public Object invoke(Pipe pipe, Object context) throws IOException {
            var cp = (CorePipe) pipe;
            cp.mMode = CorePipe.M_CLIENT;
            recycleConnection(cp);
            return BatchedContext.STOP_READING;
        }
    }

    private final class ConnectWaiter {
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
