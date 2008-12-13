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

package dirmi;

import java.io.Closeable;
import java.io.IOException;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import java.util.concurrent.ScheduledExecutorService;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.cojen.util.WeakIdentityMap;

import dirmi.core.StandardSession;
import dirmi.core.StandardSessionServer;
import dirmi.core.ThreadPool;

import dirmi.io.MessageChannel;
import dirmi.io.SocketMessageProcessor;
import dirmi.io.SocketStreamAcceptor;
import dirmi.io.SocketStreamConnector;
import dirmi.io.StreamAcceptor;
import dirmi.io.StreamBroker;
import dirmi.io.StreamBrokerAcceptor;
import dirmi.io.StreamConnector;
import dirmi.io.StreamConnectorBroker;

/**
 * Sharable environment for creating and accepting remote sessions. All
 * sessions created from an environment share a thread pool.
 *
 * @author Brian S O'Neill
 */
public class Environment implements Closeable {
    private final ScheduledExecutorService mExecutor;

    private SocketMessageProcessor mMessageProcessor;

    final WeakIdentityMap<Session, Object> mSessions;
    final WeakIdentityMap<StandardSessionServer, Object> mSessionServers;

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
        mSessions = new WeakIdentityMap<Session, Object>();
        mSessionServers = new WeakIdentityMap<StandardSessionServer, Object>();
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
        return createSession(endpoint, null);
    }

    /**
     * Attempts to connect to remote host, blocking until session is
     * established.
     *
     * @param endpoint address of remote host
     * @param bindpoint address of local host; pass null for default
     */
    public Session createSession(SocketAddress endpoint, SocketAddress bindpoint)
        throws IOException
    {
        return createSession(endpoint, bindpoint, null);
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
            StreamConnector connector = new SocketStreamConnector(mExecutor, endpoint, bindpoint);
            StreamBroker broker = new StreamConnectorBroker(mExecutor, channel, connector);
            Session session = new StandardSession(mExecutor, broker, server);

            synchronized (mSessions) {
                mSessions.put(session, "");
            }

            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns immediately and asynchronously accepts sessions.
     *
     * @param port port for accepting socket connections
     * @param acceptor called as sessions are accepted
     */
    public void acceptSessions(int port, SessionAcceptor acceptor) throws IOException {
        acceptSessions(new InetSocketAddress(port), acceptor);
    }

    /**
     * Returns immediately and asynchronously accepts sessions.
     *
     * @param bindpoint address for accepting socket connections
     * @param acceptor called as sessions are accepted
     */
    public void acceptSessions(SocketAddress bindpoint, final SessionAcceptor acceptor)
        throws IOException
    {
        if (bindpoint == null) {
            throw new IllegalArgumentException("Must provide a bindpoint");
        }
        if (acceptor == null) {
            throw new IllegalArgumentException("Must provide an acceptor");
        }

        Lock lock = closeLock();
        try {
            StreamAcceptor streamAcceptor = new SocketStreamAcceptor(mExecutor, bindpoint);
            StreamBrokerAcceptor brokerAcceptor =
                new StreamBrokerAcceptor(mExecutor, streamAcceptor);
            StandardSessionServer server = new StandardSessionServer(mExecutor, brokerAcceptor);

            synchronized (mSessionServers) {
                mSessionServers.put(server, "");
            }

            SessionAcceptor copyAcceptor = new SessionAcceptor() {
                public Object createServer() {
                    return acceptor.createServer();
                }

                public void established(Session session) {
                    Lock lock;
                    try {
                        lock = closeLock();
                    } catch (IOException e) {
                        failed(e);
                        return;
                    }
                    try {
                        synchronized (mSessions) {
                            mSessions.put(session, "");
                        }
                    } finally {
                        lock.unlock();
                    }
                    acceptor.established(session);
                }

                public void failed(IOException e) {
                    if (!isClosed()) {
                        acceptor.failed(e);
                    }
                }
            };

            server.accept(copyAcceptor);
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

            synchronized (mSessionServers) {
                for (StandardSessionServer server : mSessionServers.keySet()) {
                    try {
                        server.close();
                    } catch (IOException e) {
                        if (exception == null) {
                            exception = e;
                        }
                    }
                }

                mSessionServers.clear();
            }

            synchronized (mSessions) {
                for (Session session : mSessions.keySet()) {
                    try {
                        session.close();
                    } catch (IOException e) {
                        if (exception == null) {
                            exception = e;
                        }
                    }
                }

                mSessions.clear();
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

    private synchronized SocketMessageProcessor messageProcessor() throws IOException {
        SocketMessageProcessor processor = mMessageProcessor;
        if (processor == null) {
            mMessageProcessor = processor = new SocketMessageProcessor(mExecutor);
        }
        return processor;
    }
}
