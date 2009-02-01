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
import org.cojen.dirmi.core.StandardSessionAcceptor;

import org.cojen.dirmi.io.Acceptor;
import org.cojen.dirmi.io.AcceptListener;
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
 * sessions created from an environment share an executor.
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
     * Attempts to connect to remote host, blocking until session is
     * established.
     *
     * @param host required name of remote host
     * @param port remote port
     */
    public Session createSession(String host, int port) throws IOException {
        return createSession(new InetSocketAddress(host, port));
    }

    /**
     * Attempts to connect to remote host, blocking until session is
     * established.
     *
     * @param remoteAddress required address of remote host
     */
    public Session createSession(SocketAddress remoteAddress) throws IOException {
        return createSession(remoteAddress, null);
    }

    /**
     * Attempts to connect to remote host, blocking until session is
     * established.
     *
     * @param remoteAddress required address of remote host
     * @param localAddress optional address of local host
     */
    public Session createSession(SocketAddress remoteAddress, SocketAddress localAddress)
        throws IOException
    {
        Lock lock = closeLock();
        try {
            SocketMessageProcessor processor = messageProcessor();
            MessageChannel channel = processor.newConnector(remoteAddress, localAddress).connect();
            Connector<StreamChannel> connector =
                new SocketStreamChannelConnector(mExecutor, remoteAddress, localAddress);
            Broker<StreamChannel> broker =
                new StreamChannelConnectorBroker(mExecutor, channel, connector);
            return createSession(broker);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to connect using given broker, blocking until session is
     * established. Only one session can be created per broker instance.
     *
     * @param broker required broker for establishing connections; must always
     * connect to same remote host
     */
    public Session createSession(Broker<StreamChannel> broker) throws IOException {
        Lock lock = closeLock();
        try {
            Session session = new StandardSession(mExecutor, broker);
            addToClosableSet(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an acceptor of sessions. Call {@link SessionAcceptor#acceptAll
     * acceptAll} to start automatically accepting sessions.
     *
     * @param port port for accepting socket connections
     */
    public SessionAcceptor createSessionAcceptor(int port) throws IOException {
        return createSessionAcceptor(new InetSocketAddress(port));
    }

    /**
     * Returns an acceptor of sessions. Call {@link SessionAcceptor#acceptAll
     * acceptAll} to start automatically accepting sessions.
     *
     * @param localAddress address for accepting socket connections; use null to
     * automatically select a local address and ephemeral port
     */
    public SessionAcceptor createSessionAcceptor(SocketAddress localAddress)
        throws IOException
    {
        Lock lock = closeLock();
        try {
            return new StandardSessionAcceptor(this, createBrokerAcceptor(localAddress));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an acceptor used for asynchronously accepting brokers. Sessions
     * can be created from brokers by calling the {@link #createSession(Broker,
     * Object)} method.
     *
     * @param localAddress address for accepting socket connections; use null to
     * automatically select a local address and ephemeral port
     * @return an acceptor of brokers
     */
    private Acceptor<Broker<StreamChannel>> createBrokerAcceptor(SocketAddress localAddress)
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
     * Returns the executor used by this environment.
     */
    public ScheduledExecutorService executor() {
        return mExecutor;
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
