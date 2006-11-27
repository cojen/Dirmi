/*
 *  Copyright 2006 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dirmi.io;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.Map;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import dirmi.Asynchronous;
import dirmi.AsynchronousInvocationException;

import dirmi.core.Identifier;
import dirmi.core.Skeleton;

import dirmi.info.RemoteInfo;

/**
 * Principal implementation of a RemoteBroker. At least one thread must be
 * calling the Accepter's accept method at all times in order for accept
 * exceptions to be propagated.
 *
 * @author Brian S O'Neill
 */
public class PrincipalRemoteBroker extends AbstractRemoteBroker {
    //private static final int DEFAULT_HEARTBEAT_DELAY_MILLIS = 60000;

    final Executor mExecutor;
    final Broker mBroker;

    // Identifies our PrincipalRemoteBroker instance.
    final Identifier mLocalID;
    // Identifies remote PrincipalRemoteBroker instance.
    final Identifier mRemoteID;

    final BlockingQueue<RemoteConnection> mAcceptQueue;

    final Map<Identifier, Skeleton> mSkeletons;

    /**
     * @param executor used to execute remote methods
     * @param master single connection which is multiplexed
     */
    public PrincipalRemoteBroker(Executor executor, Connection master) throws IOException {
        this(executor, new Multiplexer(master));
    }

    /**
     * @param executor used to execute remote methods
     * @param broker connection broker must always connect to same remote server
     */
    public PrincipalRemoteBroker(Executor executor, Broker broker) throws IOException {
        this(executor, broker, new SynchronousQueue<RemoteConnection>());
    }

    /**
     * @param executor used to execute remote methods
     * @param broker connection broker must always connect to same remote server
     * @param queue accept queue
     */
    public PrincipalRemoteBroker(Executor executor, Broker broker,
                                 BlockingQueue<RemoteConnection> queue)
        throws IOException
    {
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (queue == null) {
            throw new IllegalArgumentException("Queue is null");
        }

        mExecutor = executor;
        mBroker = broker;
        mAcceptQueue = queue;

        mLocalID = Identifier.identify(this);

        // Transmit our identifier. Do so in a separate thread because remote
        // side cannot accept our connection until it sends its identifier. This
        // strategy avoids instant deadlock.
        class SendID implements Runnable {
            IOException error;
            boolean done;

            public synchronized void run() {
                try {
                    Connection con = mBroker.connecter().connect();
                    RemoteOutputStream out = new RemoteOutputStream(con.getOutputStream());
                    mLocalID.write(out);
                    out.close();
                } catch (IOException e) {
                    error = e;
                } finally {
                    done = true;
                    notify();
                }
            }

            synchronized void waitUntilDone() throws IOException {
                while (!done) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
                if (error != null) {
                    throw error;
                }
            }
        }

        SendID sendID = new SendID();
        executor.execute(sendID);

        // Accept connection and get remote identifier.
        Connection con = mBroker.accepter().accept();
        RemoteInputStream in = new RemoteInputStream(con.getInputStream());
        mRemoteID = Identifier.read(in);
        in.close();

        // Wait for our identifier to send.
        sendID.waitUntilDone();

        mSkeletons = new ConcurrentHashMap<Identifier, Skeleton>();

        // Fire up the initial accept thread.
        executor.execute(new AcceptThread());
    }

    protected RemoteConnection connect() throws IOException {
        return connected(mBroker.connecter().connect());
    }

    protected RemoteConnection connect(int timeoutMillis) throws IOException {
        return connected(mBroker.connecter().connect(timeoutMillis));
    }

    private RemoteCon connected(Connection con) throws IOException {
        RemoteCon remoteCon = new RemoteCon(con);
        // Write remote broker ID to indicate that this connection is not to be
        // used for invoking a remote method.
        RemoteOutputStream out = remoteCon.getOutputStream();
        mRemoteID.write(out);
        return remoteCon;
    }

    protected RemoteConnection accept() throws IOException {
        try {
            RemoteConnection con = mAcceptQueue.take();
            if (con instanceof BrokenCon) {
                // Forces exception to be thrown.
                con.close();
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    protected RemoteConnection accept(int timeoutMillis) throws IOException {
        try {
            RemoteConnection con = mAcceptQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (con instanceof BrokenCon) {
                // Forces exception to be thrown.
                con.close();
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    private class AcceptThread implements Runnable {
        public void run() {
            Connection con;
            try {
                while (true) {
                    try {
                        con = mBroker.accepter().accept();
                        break;
                    } catch (IOException e) {
                        try {
                            mAcceptQueue.put(new BrokenCon(e));
                        } catch (InterruptedException e2) {
                            // Don't care.
                        } 
                    }
                }
            } finally {
                // Spawn a replacement.
                mExecutor.execute(new AcceptThread());
            }

            try {
                RemoteCon remoteCon = new RemoteCon(con);

                // Decide if connection is to be used for invoking method or to
                // be passed to accept queue.

                RemoteInputStream in = remoteCon.getInputStream();
                Identifier id = Identifier.read(in);

                if (id.equals(mLocalID)) {
                    // Magic ID to indicate connection is for accept queue.
                    try {
                        mAcceptQueue.put(remoteCon);
                    } catch (InterruptedException e) {
                        try {
                            remoteCon.close();
                        } catch (IOException e2) {
                            // Don't care.
                        }
                    }
                } else {
                    // Find a Skeleton to invoke.
                    Skeleton skeleton = mSkeletons.get(id);

                    Throwable throwable = null;
                    if (skeleton == null) {
                        throwable = new NoSuchObjectException
                            ("Server cannot find remote object: " + id);
                    } else {
                        try {
                            skeleton.invoke(remoteCon);
                        } catch (NoSuchMethodException e) {
                            throwable = e;
                        } catch (NoSuchObjectException e) {
                            throwable = e;
                        } catch (ClassNotFoundException e) {
                            throwable = e;
                        } catch (AsynchronousInvocationException e) {
                            Thread t = Thread.currentThread();
                            t.getUncaughtExceptionHandler().uncaughtException(t, e);
                        }
                    }

                    if (throwable != null) {
                        remoteCon.getOutputStream().writeThrowable(throwable);
                        remoteCon.close();
                    }
                }
            } catch (IOException e) {
                try {
                    con.close();
                } catch (IOException e2) {
                    // Don't care.
                }
                try {
                    mAcceptQueue.put(new BrokenCon(e));
                } catch (InterruptedException e2) {
                    // Don't care.
                } 
            }
        }
    }

    private class RemoteCon implements RemoteConnection {
        private final Connection mCon;
        private final RemoteInputStream mRemoteIn;
        private final RemoteOutputStream mRemoteOut;

        RemoteCon(Connection con) throws IOException {
            mCon = con;
            // FIXME: Use stream subclasses and supply address string.
            mRemoteIn = new RemoteInputStream(con.getInputStream());
            mRemoteOut = new RemoteOutputStream(con.getOutputStream());
        }

        public void close() throws IOException {
            mCon.close();
        }

        public RemoteInputStream getInputStream() throws IOException {
            return mRemoteIn;
        }

        public RemoteOutputStream getOutputStream() throws IOException {
            return mRemoteOut;
        }

        public void dispose(Identifier id) throws RemoteException {
            // FIXME: cancel lease
            // FIXME: implement
        }
    }

    /**
     * Used to pass an IOException along.
     */
    private static class BrokenCon implements RemoteConnection {
        private final IOException mException;

        BrokenCon(IOException exception) {
            mException = exception;
        }

        public void close() throws IOException {
            throw mException;
        }

        public RemoteInputStream getInputStream() throws IOException {
            throw mException;
        }

        public RemoteOutputStream getOutputStream() throws IOException {
            throw mException;
        }

        public void dispose(Identifier id) {
        }
    }

    private static class Hidden {
        public static interface Admin extends Remote {
            /**
             * Returns RemoteInfo object from server.
             */
            RemoteInfo getRemoteInfo(Identifier id) throws RemoteException;

            /**
             * Notification from client when it has disposed of an identified object.
             */
            @Asynchronous
            void disposed(Identifier id) throws RemoteException;

            /**
             * Notification from client that it is alive.
             *
             * @return maximum milliseconds to wait before sending next heartbeat
             */
            long heartbeat() throws RemoteException;
        }
    }
}
