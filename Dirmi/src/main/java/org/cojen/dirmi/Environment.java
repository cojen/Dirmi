/*
 *  Copyright 2008 Brian S O'Neill
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

package org.cojen.dirmi;

import java.io.Closeable;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.nio.channels.ClosedByInterruptException;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.cojen.util.WeakIdentityMap;

import org.cojen.dirmi.core.StandardSession;
import org.cojen.dirmi.core.StandardSessionAcceptor;

import org.cojen.dirmi.io.Acceptor;
import org.cojen.dirmi.io.AcceptListener;
import org.cojen.dirmi.io.Broker;
import org.cojen.dirmi.io.Connector;
import org.cojen.dirmi.io.MessageChannel;
import org.cojen.dirmi.io.PipedBroker;
import org.cojen.dirmi.io.SocketMessageProcessor;
import org.cojen.dirmi.io.SocketStreamChannelAcceptor;
import org.cojen.dirmi.io.SocketStreamChannelConnector;
import org.cojen.dirmi.io.StreamChannel;
import org.cojen.dirmi.io.StreamChannelBrokerAcceptor;
import org.cojen.dirmi.io.StreamChannelConnectorBroker;

import org.cojen.dirmi.util.ThreadPool;

/**
 * Sharable environment for connecting and accepting remote sessions. All
 * sessions established from the same environment instance share an executor.
 *
 * @author Brian S O'Neill
 */
public class Environment implements Closeable {
    final ScheduledExecutorService mExecutor;

    SocketMessageProcessor mMessageProcessor;

    final WeakIdentityMap<Closeable, Object> mCloseableSet;

    private final ReadWriteLock mCloseLock;
    private boolean mClosed;

    /**
     * Construct environment which uses up to 1000 threads.
     */
    public Environment() {
        this(1000);
    }

    /**
     * Construct environment with the given maximum number of threads.
     */
    public Environment(int maxThreads) {
        this(new ThreadPool(maxThreads, false, "dirmi"));
    }

    /**
     * Construct environment with the given maximum number of threads and
     * uncaught exception handler.
     */
    public Environment(int maxThreads, Thread.UncaughtExceptionHandler handler) {
        this(new ThreadPool(maxThreads, false, "dirmi", handler));
    }

    /**
     * Construct environment with a custom executor.
     */
    public Environment(ScheduledExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Must provide an executor");
        }
        mExecutor = executor;
        mCloseableSet = new WeakIdentityMap<Closeable, Object>();
        mCloseLock = new ReentrantReadWriteLock(true);
    }

    /**
     * Returns a session connector for a remote endpoint. Call {@link
     * SessionConnector#connect connect} to immediately establish a session.
     *
     * @param host required name of remote endpoint
     * @param port remote port
     */
    public SessionConnector newSessionConnector(String host, int port) {
        return newSessionConnector(new InetSocketAddress(host, port));
    }

    /**
     * Returns a session connector for a remote endpoint. Call {@link
     * SessionConnector#connect connect} to immediately establish a session.
     *
     * @param remoteAddress required address of remote endpoint
     */
    public SessionConnector newSessionConnector(SocketAddress remoteAddress) {
        return newSessionConnector(remoteAddress, null);
    }

    /**
     * Returns a session connector for a remote endpoint. Call {@link
     * SessionConnector#connect connect} to immediately establish a session.
     *
     * @param remoteAddress required address of remote endpoint
     * @param localAddress optional address of local bindpoint
     */
    public SessionConnector newSessionConnector(SocketAddress remoteAddress,
                                                SocketAddress localAddress)
    {
        return new SocketConnector(remoteAddress, localAddress);
    }

    /**
     * Returns an acceptor of sessions. Call {@link SessionAcceptor#acceptAll
     * acceptAll} to start automatically accepting sessions.
     *
     * @param port port for accepting socket connections
     */
    public SessionAcceptor newSessionAcceptor(int port) throws IOException {
        return newSessionAcceptor(new InetSocketAddress(port));
    }

    /**
     * Returns an acceptor of sessions. Call {@link SessionAcceptor#acceptAll
     * acceptAll} to start automatically accepting sessions.
     *
     * @param localAddress address for accepting socket connections; use null to
     * automatically select a local address and ephemeral port
     */
    public SessionAcceptor newSessionAcceptor(SocketAddress localAddress) throws IOException {
        Lock lock = closeLock();
        try {
            return new StandardSessionAcceptor(this, newBrokerAcceptor(localAddress));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an acceptor used for asynchronously accepting brokers. Sessions
     * can be created from brokers by calling the {@link #newSession(Broker,
     * Object)} method.
     *
     * @param localAddress address for accepting socket connections; use null to
     * automatically select a local address and ephemeral port
     * @return an acceptor of brokers
     */
    private Acceptor<Broker<StreamChannel>> newBrokerAcceptor(SocketAddress localAddress)
        throws IOException
    {
        Lock lock = closeLock();
        try {
            Acceptor<StreamChannel> streamAcceptor =
                new SocketStreamChannelAcceptor(mExecutor, localAddress);
            Acceptor<Broker<StreamChannel>> brokerAcceptor =
                new StreamChannelBrokerAcceptor(mExecutor, streamAcceptor);
            addToClosableSet(brokerAcceptor);
            return brokerAcceptor;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to connect using given broker, blocking until session is
     * established. Only one session can be created per broker instance.
     *
     * @param broker required broker for establishing connections; must always
     * connect to same remote endpoint
     */
    public Session newSession(Broker<StreamChannel> broker) throws IOException {
        // Creating session can block, so do it without holding close lock.

        addToClosableSet(broker);
        Session session = new StandardSession(mExecutor, broker);

        Lock lock = closeLock();
        try {
            // Let session manage close of broker.
            addToClosableSet(session);
            removeFromCloseableSet(broker);
        } finally {
            lock.unlock();
        }

        return session;
    }

    /**
     * Attempts to connect using given broker, blocking until session is
     * established. Only one session can be created per broker instance.
     *
     * @param broker required broker for establishing connections; must always
     * connect to same remote endpoint
     * @throws RemoteTimeoutException
     */
    public Session newSession(Broker<StreamChannel> broker, long timeout, TimeUnit unit)
        throws IOException
    {
        if (timeout < 0) {
            return newSession(broker);
        }

        InterruptTask task = new InterruptTask();
        Future<?> future = mExecutor.schedule(task, timeout, unit);

        try {
            return newSession(broker);
        } catch (IOException e) {
            throw handleTimeout(e, timeout, unit);
        } finally {
            task.cancel(future);
        }
    }

    /**
     * Returns two locally connected sessions.
     *
     * @return two Session objects connected to each other
     * @throws RejectedExecutionException if thread pool is full
     */
    public Session[] newSessionPair() {
        final PipedBroker broker_0, broker_1;
        broker_0 = new PipedBroker(mExecutor);
        try {
            broker_1 = new PipedBroker(mExecutor, broker_0);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        class Create implements Runnable {
            private IOException mException;
            private Session mSession;

            public synchronized void run() {
                try {
                    mSession = newSession(broker_0);
                } catch (IOException e) {
                    mException = e;
                }
                notifyAll();
            }

            public synchronized Session waitForSession() throws IOException {
                while (mException == null && mSession == null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Ignore.
                    }
                }
                if (mException != null) {
                    throw mException;
                }
                return mSession;
            }
        }

        Create create = new Create();
        mExecutor.execute(create);

        final Session session_0, session_1;
        try {
            session_0 = newSession(broker_1);
            session_1 = create.waitForSession();
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        return new Session[] {session_0, session_1};
    }

    /**
     * Returns the executor used by this environment.
     */
    public ScheduledExecutorService executor() {
        return mExecutor;
    }

    /**
     * Closes all existing sessions and then shuts down the thread pool. New
     * sessions cannot be established.
     */
    public void close() throws IOException {
        Lock lock = mCloseLock.writeLock();
        lock.lock();
        try {
            if (mClosed) {
                return;
            }

            IOException exception = null;

            synchronized (mCloseableSet) {
                for (Closeable c : mCloseableSet.keySet()) {
                    try {
                        c.close();
                    } catch (IOException e) {
                        if (exception == null) {
                            exception = e;
                        }
                    }
                }

                mCloseableSet.clear();
            }

            mExecutor.shutdownNow();

            mClosed = true;

            if (exception != null) {
                throw exception;
            }
        } finally {
            lock.unlock();
        }
    }

    boolean isClosed() {
        Lock lock = mCloseLock.readLock();
        lock.lock();
        try {
            return mClosed;
        } finally {
            lock.unlock();
        }
    }

    Lock closeLock() throws IOException {
        Lock lock = mCloseLock.readLock();
        lock.lock();
        if (mClosed) {
            lock.unlock();
            throw new IOException("Environment is closed");
        }
        return lock;
    }

    void addToClosableSet(Closeable c) throws IOException {
        Lock lock = closeLock();
        try {
            synchronized (mCloseableSet) {
                mCloseableSet.put(c, "");
            }
        } finally {
            lock.unlock();
        }
    }

    void removeFromCloseableSet(Closeable c) {
        synchronized (mCloseableSet) {
            mCloseableSet.remove(c);
        }
    }

    SocketMessageProcessor messageProcessor() throws IOException {
        Lock lock = closeLock();
        try {
            SocketMessageProcessor processor = mMessageProcessor;
            if (processor != null) {
                return processor;
            }
        } finally {
            lock.unlock();
        }

        (lock = mCloseLock.writeLock()).lock();
        try {
            SocketMessageProcessor processor = mMessageProcessor;
            if (processor == null) {
                mMessageProcessor = processor = new SocketMessageProcessor(mExecutor);
                addToClosableSet(processor);
            }
            return processor;
        } finally {
            lock.unlock();
        }
    }

    static IOException handleTimeout(Exception e, long timeout, TimeUnit unit) {
        if (e instanceof RemoteTimeoutException) {
            return (RemoteTimeoutException) e;
        }

        if (e instanceof InterruptedException ||
            e instanceof InterruptedIOException ||
            e instanceof ClosedByInterruptException)
        {
            return new RemoteTimeoutException(timeout, unit);
        }

        Throwable cause = e.getCause();

        while (cause != null && cause instanceof Exception) {
            IOException newEx = handleTimeout((Exception) cause, timeout, unit);
            if (newEx instanceof RemoteTimeoutException) {
                return newEx;
            }
            cause = cause.getCause();
        }

        if (e instanceof IOException) {
            return (IOException) e;
        }

        IOException ioe = new IOException(e.toString());
        ioe.initCause(e);
        return ioe;
    }

    private class SocketConnector implements SessionConnector {
        private final SocketAddress mRemoteAddress;
        private final SocketAddress mLocalAddress;

        SocketConnector(SocketAddress remoteAddress, SocketAddress localAddress) {
            if (remoteAddress == null) {
                throw new IllegalArgumentException("Must provide a remote address");
            }
            mRemoteAddress = remoteAddress;
            mLocalAddress = localAddress;
        }

        public Session connect() throws IOException {
            // Creating broker can block, so do it without holding close lock.

            SocketMessageProcessor processor = messageProcessor();
            MessageChannel channel =
                processor.newConnector(mRemoteAddress, mLocalAddress).connect();
            addToClosableSet(channel);
            Connector<StreamChannel> connector =
                new SocketStreamChannelConnector(mExecutor, mRemoteAddress, mLocalAddress);

            Broker<StreamChannel> broker =
                new StreamChannelConnectorBroker(mExecutor, channel, connector);

            Lock lock = closeLock();
            try {
                // Let broker manage close of control channel.
                addToClosableSet(broker);
                removeFromCloseableSet(channel);
            } finally {
                lock.unlock();
            }

            return newSession(broker);
        }

        public Session connect(long timeout, TimeUnit unit) throws IOException {
            if (timeout < 0) {
                return connect();
            }

            InterruptTask task = new InterruptTask();
            Future<?> future = mExecutor.schedule(task, timeout, unit);

            try {
                return connect();
            } catch (IOException e) {
                throw handleTimeout(e, timeout, unit);
            } finally {
                task.cancel(future);
            }
        }

        public Object getRemoteAddress() {
            return mRemoteAddress;
        }

        public Object getLocalAddress() {
            return mLocalAddress;
        }
    }

    private static class InterruptTask implements Runnable {
        private final Thread mThread;
        private boolean mCancel;

        InterruptTask() {
            mThread = Thread.currentThread();
        }

        public synchronized void run() {
            if (!mCancel) {
                mThread.interrupt();
            }
        }

        synchronized void cancel(Future<?> future) {
            mCancel = true;
            future.cancel(false);
            // Clear interrupted status.
            Thread.interrupted();
        }
    }
}
