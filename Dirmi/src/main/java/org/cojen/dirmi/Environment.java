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
import java.io.IOException;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import java.util.concurrent.ScheduledExecutorService;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.cojen.util.WeakIdentityMap;

import org.cojen.dirmi.core.StandardSession;

import org.cojen.dirmi.io.Acceptor;
import org.cojen.dirmi.io.Broker;
import org.cojen.dirmi.io.Connector;
import org.cojen.dirmi.io.MessageChannel;
import org.cojen.dirmi.io.SocketMessageProcessor;
import org.cojen.dirmi.io.SocketStreamChannelAcceptor;
import org.cojen.dirmi.io.SocketStreamChannelConnector;
import org.cojen.dirmi.io.StreamChannel;
import org.cojen.dirmi.io.StreamChannelBrokerAcceptor;
import org.cojen.dirmi.io.StreamChannelConnectorBroker;

import org.cojen.dirmi.util.ThreadPool;

/**
 * Sharable environment for creating and accepting remote sessions. All
 * sessions created from an environment share a thread pool.
 *
 * @author Brian S O'Neill
 */
public class Environment implements Closeable {
    private final ScheduledExecutorService mExecutor;

    private SocketMessageProcessor mMessageProcessor;

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
     * Construct environment with a custom thread pool.
     */
    public Environment(ScheduledExecutorService executor) {
        mExecutor = executor;
        mCloseableSet = new WeakIdentityMap<Closeable, Object>();
        mCloseLock = new ReentrantReadWriteLock(true);
    }

    /**
     * Attempts to connect to remote host, blocking until session is
     * established.
     *
     * @param host name of remote host
     * @param port remote port
     */
    public Session createSession(String host, int port) throws IOException {
        return createSession(new InetSocketAddress(host, port));
    }

    /**
     * Attempts to connect to remote host, blocking until session is
     * established.
     *
     * @param endpoint address of remote host
     */
    public Session createSession(SocketAddress endpoint) throws IOException {
        return createSession(endpoint, null, null);
    }

    /**
     * Attempts to connect to remote host, blocking until session is
     * established.
     *
     * @param endpoint address of remote host
     * @param bindpoint address of local host; pass null for default
     * @param server optional primary server object to export; must be Remote
     * or Serializable
     */
    public Session createSession(SocketAddress endpoint, SocketAddress bindpoint, Object server)
        throws IOException
    {
        Lock lock = closeLock();
        try {
            SocketMessageProcessor processor = messageProcessor();
            MessageChannel channel = processor.newConnector(endpoint, bindpoint).connect();
            Connector<StreamChannel> connector =
                new SocketStreamChannelConnector(mExecutor, endpoint, bindpoint);
            Broker<StreamChannel> broker =
                new StreamChannelConnectorBroker(mExecutor, channel, connector);

            return createSession(broker, server);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to connect to remote host, blocking until session is
     * established. Only one session can be created per broker instance.
     *
     * @param broker broker for establishing connections; must always connect to same remote host
     * @param server optional primary server object to export; must be Remote
     * or Serializable
     */
    public Session createSession(Broker<StreamChannel> broker, Object server) throws IOException {
        Lock lock = closeLock();
        try {
            Session session = new StandardSession(mExecutor, broker, server);
            addToClosableSet(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns immediately and asynchronously accepts sessions. Any exceptions
     * during session establishment are passed to the thread's uncaught
     * exception handler.
     *
     * @param port port for accepting socket connections
     * @param server primary server object to export; must be Remote or
     * Serializable
     * @return object which can be closed to stop accepting sessions
     */
    public Closeable acceptSessions(int port, Object server) throws IOException {
        return acceptSessions(new InetSocketAddress(port), server);
    }

    /**
     * Returns immediately and asynchronously accepts sessions. Any exceptions
     * during session establishment are passed to the thread's uncaught
     * exception handler.
     *
     * @param bindpoint address for accepting socket connections
     * @param server shared server object to export; must be Remote or
     * Serializable
     * @return object which can be closed to stop accepting sessions
     */
    public Closeable acceptSessions(SocketAddress bindpoint, final Object server)
        throws IOException
    {
        Lock lock = closeLock();
        try {
            final Acceptor<Broker<StreamChannel>> brokerAcceptor = createBrokerAcceptor(bindpoint);

            brokerAcceptor.accept(new Acceptor.Listener<Broker<StreamChannel>>() {
                public void established(Broker<StreamChannel> broker) {
                    brokerAcceptor.accept(this);

                    try {
                        createSession(broker, server);
                    } catch (IOException e) {
                        try {
                            broker.close();
                        } catch (IOException e2) {
                            // Ignore.
                        }
                    }
                }

                public void failed(IOException e) {
                    Thread t = Thread.currentThread();
                    try {
                        t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    } catch (Throwable e2) {
                        // I give up.
                    }
                    // Yield just in case exceptions are out of control.
                    t.yield();
                }
            });

            return brokerAcceptor;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns immediately and asynchronously accepts brokers. Sessions can be
     * created from brokers by calling the {@link #createSession(Broker,
     * Object)} method. Compared to the {@link #acceptSessions}, this method
     * provides more control over how sessions are accepted.
     *
     * @param bindpoint address for accepting socket connections
     * @return an acceptor of brokers
     */
    public Acceptor<Broker<StreamChannel>> createBrokerAcceptor(SocketAddress bindpoint)
        throws IOException
    {
        if (bindpoint == null) {
            throw new IllegalArgumentException("Must provide a bindpoint");
        }
        Lock lock = closeLock();
        try {
            Acceptor<StreamChannel> streamAcceptor =
                new SocketStreamChannelAcceptor(mExecutor, bindpoint);
            Acceptor<Broker<StreamChannel>> brokerAcceptor =
                new StreamChannelBrokerAcceptor(mExecutor, streamAcceptor);
            addToClosableSet(brokerAcceptor);
            return brokerAcceptor;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes all existing sessions and then shuts down the thread pool. New
     * sessions cannot be created.
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

    private synchronized SocketMessageProcessor messageProcessor() throws IOException {
        SocketMessageProcessor processor = mMessageProcessor;
        if (processor == null) {
            mMessageProcessor = processor = new SocketMessageProcessor(mExecutor);
            addToClosableSet(processor);
        }
        return processor;
    }
}
