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

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class ServerSession<R> extends CoreSession<R> {
    private final Skeleton<R> mRoot;

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
        mSkeletons.put(new Connector());
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
    }

    @Override
    public R root() {
        return mRoot.server();
    }

    @Override
    public void connected(SocketAddress localAddr, SocketAddress remoteAttr,
                          InputStream in, OutputStream out)
        throws IOException
    {
        // FIXME: reject?
        throw null;
    }

    /**
     * @param pipe must have M_SERVER mode
     */
    void accepted(CorePipe pipe) throws IOException {
        registerNewConnection(pipe);
        startRequestProcessor(pipe);
    }

    @Override
    boolean recycleConnection(CorePipe pipe) {
        if (!super.recycleConnection(pipe)) {
            return false;
        }

        conLockAcquire();
        try {
            ConnectWaiter first = mFirstWaiter;
            if (first != null) {
                first.mSignaled = true;
                LockSupport.unpark(first.mThread);
                ConnectWaiter next = first.mNext;
                mFirstWaiter = next;
                if (next == null) {
                    mLastWaiter = null;
                }
            }
        } finally {
            conLockRelease();
        }

        return true;
    }

    @Override
    CorePipe connect() throws IOException {
        ConnectWaiter waiter = null;

        while (true) {
            CorePipe pipe = tryObtainConnection();

            if (pipe != null) {
                return pipe;
            }

            mControlLock.lock();
            try {
                mControlPipe.write(C_REQUEST_CONNECTION);
                // Pass object 0, which refers to the Connector instance.
                mControlPipe.writeLong(0);
                mControlPipe.flush();
            } finally {
                mControlLock.unlock();
            }

            if (waiter == null) {
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

            LockSupport.park();

            if (waiter.mSignaled) {
                waiter = null;
            }
        }
    }

    /**
     * Pseudo skeleton used for accepting reverse connections from the client.
     */
    private final class Connector extends Skeleton {
        Connector() {
            super(0);
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
        volatile boolean mSignaled;

        ConnectWaiter(Thread thread) {
            mThread = thread;
        }
    }
}
