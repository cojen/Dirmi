/*
 *  Copyright 2008-2010 Brian S O'Neill
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
import java.net.ServerSocket;
import java.net.SocketAddress;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.cojen.util.WeakIdentityMap;

import org.cojen.dirmi.core.StandardSession;
import org.cojen.dirmi.core.StandardSessionAcceptor;

import org.cojen.dirmi.io.BasicChannelBrokerAcceptor;
import org.cojen.dirmi.io.BasicChannelBrokerConnector;
import org.cojen.dirmi.io.BufferedSocketChannelAcceptor;
import org.cojen.dirmi.io.BufferedSocketChannelConnector;
import org.cojen.dirmi.io.ChannelAcceptor;
import org.cojen.dirmi.io.ChannelBroker;
import org.cojen.dirmi.io.ChannelBrokerAcceptor;
import org.cojen.dirmi.io.ChannelBrokerConnector;
import org.cojen.dirmi.io.ChannelConnector;
import org.cojen.dirmi.io.IOExecutor;
import org.cojen.dirmi.io.PipedChannelBroker;
import org.cojen.dirmi.io.RecyclableSocketChannelAcceptor;
import org.cojen.dirmi.io.RecyclableSocketChannelConnector;
import org.cojen.dirmi.io.RecyclableSocketChannelSelector;
import org.cojen.dirmi.io.SocketChannelSelector;

import org.cojen.dirmi.util.ThreadPool;
import org.cojen.dirmi.util.Timer;

/**
 * Sharable environment for connecting and accepting remote sessions. All
 * sessions established from the same environment instance share an executor.
 *
 * @author Brian S O'Neill
 */
public class Environment implements Closeable {
    private static final boolean RECYCLABLE_SOCKETS;

    static {
        boolean recyclableSockets = true;
        try {
            String prop = System.getProperty("org.cojen.dirmi.Environment.recyclableSockets");
            if (prop != null && prop.equalsIgnoreCase("false")) {
                recyclableSockets = false;
            }
        } catch (SecurityException e) {
        }

        RECYCLABLE_SOCKETS = recyclableSockets;
    }

    private final ScheduledExecutorService mExecutor;
    private final IOExecutor mIOExecutor;

    private final WeakIdentityMap<Closeable, Object> mCloseableSet;
    private final AtomicBoolean mClosed;
    private final boolean mRecyclableSockets = RECYCLABLE_SOCKETS;

    private final SocketFactory mSocketFactory;
    private final ServerSocketFactory mServerSocketFactory;

    private final RecyclableSocketChannelSelector mSelector;

    /**
     * Construct environment which uses up to 1000 threads.
     */
    public Environment() {
        this(1000);
    }

    /**
     * Construct environment with the given maximum number of threads.
     *
     * @param maxThreads maximum number of threads in pool
     */
    public Environment(int maxThreads) {
        this(maxThreads, null, null);
    }

    /**
     * Construct environment with the given maximum number of threads, thread
     * name prefix, and uncaught exception handler.
     *
     * @param maxThreads maximum number of threads in pool
     * @param threadNamePrefix prefix given to thread name; pass null for default
     * @param handler handler for uncaught exceptions; pass null for default
     */
    public Environment(int maxThreads, String threadNamePrefix,
                       Thread.UncaughtExceptionHandler handler)
    {
        this(new ThreadPool(maxThreads,
                            false,
                            threadNamePrefix == null ? "dirmi" : threadNamePrefix,
                            handler));
    }

    /**
     * Construct environment with a custom executor.
     *
     * @see ThreadPool
     */
    public Environment(ScheduledExecutorService executor) {
        this(executor, null, null, null, null, null, null);
    }

    private Environment(ScheduledExecutorService executor,
                        IOExecutor ioExecutor,
                        WeakIdentityMap<Closeable, Object> closeable,
                        AtomicBoolean closed,
                        SocketFactory sf,
                        ServerSocketFactory ssf,
                        RecyclableSocketChannelSelector selector)
    {
        if (executor == null) {
            throw new IllegalArgumentException("Must provide an executor");
        }
        mExecutor = executor;
        mIOExecutor = ioExecutor == null ? new IOExecutor(executor) : ioExecutor;
        mCloseableSet = closeable == null ? new WeakIdentityMap<Closeable, Object>() : closeable;
        mClosed = closed == null ? new AtomicBoolean(false) : closed;
        mSocketFactory = sf;
        mServerSocketFactory = ssf;
        mSelector = selector;
    }

    /**
     * Returns an environment instance which connects using the given client
     * socket factory. The returned environment is linked to this one, and
     * closing either environment closes both.
     *
     * @throws IllegalStateException if environment uses a selector
     */
    public Environment withClientSocketFactory(SocketFactory sf) {
        if (mSelector != null) {
            throw new IllegalStateException("Cannot combine socket factory and selector");
        }
        return new Environment(mExecutor, mIOExecutor, mCloseableSet, mClosed,
                               sf, mServerSocketFactory, null);
    }

    /**
     * Returns an environment instance which accepts using the given server
     * socket factory. The returned environment is linked to this one, and
     * closing either environment closes both.
     *
     * @throws IllegalStateException if environment uses a selector
     */
    public Environment withServerSocketFactory(ServerSocketFactory ssf) {
        if (mSelector != null) {
            throw new IllegalStateException("Cannot combine socket factory and selector");
        }
        return new Environment(mExecutor, mIOExecutor, mCloseableSet, mClosed,
                               mSocketFactory, ssf, null);
    }

    /**
     * Returns an environment instance which uses selectable sockets, reducing
     * the amount of idle threads. The returned environment is linked to this
     * one, and closing either environment closes both.
     *
     * <p>Overall performance is lower with selectable sockets, but scalability
     * is improved. Remote call overhead is about 2 to 4 times higher, which is
     * largely due to the inefficient design of the nio library.
     *
     * @throws IllegalStateException if environment uses a socket factory
     */
    public Environment withSocketSelector() throws IOException {
        if (mSocketFactory != null || mServerSocketFactory != null) {
            throw new IllegalStateException("Cannot combine socket factory and selector");
        }
        if (!mRecyclableSockets) {
            throw new IllegalStateException("Cannot use unrecyclable sockets with selector");
        }

        // Don't allow if using buggy version.
        check: if ("Linux".equals(System.getProperty("os.name")) &&
            "Sun Microsystems Inc.".equals(System.getProperty("java.vendor")) &&
            "1.6".equals(System.getProperty("java.specification.version")))
        {
            String version = System.getProperty("java.version");
            int index = version.indexOf('_');
            if (index > 0) {
                int subVersion;
                try {
                    subVersion = Integer.parseInt(version.substring(index + 1));
                } catch (RuntimeException e) {
                    break check;
                }
                if (subVersion < 18) {
                    // select throws "File exists" IOException under load (lnx)
                    throw new IOException
                        ("Java version doesn't have fix for bug 6693490: " + version);
                }
            }
        }

        final RecyclableSocketChannelSelector selector =
            new RecyclableSocketChannelSelector(mIOExecutor);
        addToClosableSet(selector);

        mIOExecutor.execute(new Runnable() {
            public void run() {
                try {
                    selector.selectLoop();
                } catch (IOException e) {
                    org.cojen.util.ThrowUnchecked.fire(e);
                }
            }
        });

        return new Environment(mExecutor, mIOExecutor, mCloseableSet, mClosed,
                               null, null, selector);
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
     * @param port port for accepting socket connections; pass zero to choose
     * any available port
     */
    public SessionAcceptor newSessionAcceptor(int port) throws IOException {
        return newSessionAcceptor(new InetSocketAddress(port));
    }

    /**
     * Returns an acceptor of sessions. Call {@link SessionAcceptor#acceptAll
     * acceptAll} to start automatically accepting sessions.
     *
     * @param localAddress address for accepting socket connections; use null to
     * automatically select a local address and any available port
     */
    public SessionAcceptor newSessionAcceptor(SocketAddress localAddress) throws IOException {
        return StandardSessionAcceptor.create(this, newBrokerAcceptor(localAddress));
    }

    /**
     * Returns an acceptor used for asynchronously accepting brokers. Sessions
     * can be created from brokers by calling the {@link #newSession(Broker,
     * Object)} method.
     *
     * @param localAddress address for accepting socket connections; use null to
     * automatically select a local address and any available port
     * @return an acceptor of brokers
     */
    private ChannelBrokerAcceptor newBrokerAcceptor(SocketAddress localAddress)
        throws IOException
    {
        checkClosed();
        ChannelAcceptor channelAcceptor = newChannelAcceptor(localAddress);
        ChannelBrokerAcceptor brokerAcceptor =
            new BasicChannelBrokerAcceptor(mIOExecutor, channelAcceptor);
        addToClosableSet(brokerAcceptor);
        return brokerAcceptor;
    }

    /**
     * Attempts to connect using given broker, blocking until session is
     * established. Only one session can be created per broker instance.
     *
     * @param broker required broker for establishing connections; must always
     * connect to same remote endpoint
     */
    public Session newSession(ChannelBroker broker) throws IOException {
        checkClosed();
        try {
            Session session = StandardSession.create(mIOExecutor, broker);
            addToClosableSet(session);
            return session;
        } catch (IOException e) {
            broker.close();
            throw e;
        }
    }

    /**
     * Attempts to connect using given broker, blocking until session is
     * established. Only one session can be created per broker instance.
     *
     * @param broker required broker for establishing connections; must always
     * connect to same remote endpoint
     * @throws RemoteTimeoutException
     */
    public Session newSession(ChannelBroker broker, long timeout, TimeUnit unit)
        throws IOException
    {
        return timeout < 0 ? newSession(broker) : newSession(broker, new Timer(timeout, unit));
    }

    /**
     * Attempts to connect using given broker, blocking until session is
     * established. Only one session can be created per broker instance.
     *
     * @param broker required broker for establishing connections; must always
     * connect to same remote endpoint
     * @throws RemoteTimeoutException
     */
    Session newSession(ChannelBroker broker, Timer timer) throws IOException {
        checkClosed();
        try {
            Session session = StandardSession.create(mIOExecutor, broker, timer);
            addToClosableSet(session);
            return session;
        } catch (IOException e) {
            broker.close();
            throw e;
        }
    }

    /**
     * Returns two locally connected sessions.
     *
     * @return two Session objects connected to each other
     * @throws RejectedException if thread pool is full or shutdown
     */
    public Session[] newSessionPair() throws RejectedException {
        final ChannelBroker[] brokers = PipedChannelBroker.newPair(mIOExecutor);

        class Create implements Runnable {
            private IOException mException;
            private Session mSession;

            public synchronized void run() {
                try {
                    mSession = newSession(brokers[0]);
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
        mIOExecutor.execute(create);

        final Session session_0, session_1;
        try {
            session_0 = newSession(brokers[1]);
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
        boolean wasClosed = mClosed.getAndSet(true);

        IOException exception = null;

        // Copy to avoid holding lock during close.
        List<Closeable> closable;
        synchronized (mCloseableSet) {
            closable = new ArrayList<Closeable>(mCloseableSet.keySet());
            mCloseableSet.clear();
        }

        for (int i=1; i<=3; i++) {
            for (Closeable c : closable) {
                // Close sessions before brokers to avoid blocking. Close
                // selectors last, after all sockets are closed.
                if ((i == 1) == (c instanceof Session) &&
                    (i == 3) == (c instanceof SocketChannelSelector))
                {
                    try {
                        c.close();
                    } catch (IOException e) {
                        if (exception == null) {
                            exception = e;
                        }
                    }
                }
            }
        }

        if (!wasClosed) {
            mExecutor.shutdownNow();
        }

        if (exception != null) {
            throw exception;
        }
    }

    void checkClosed() throws IOException {
        if (mClosed.get()) {
            throw new IOException("Environment is closed");
        }
    }

    void addToClosableSet(Closeable c) throws IOException {
        try {
            synchronized (mCloseableSet) {
                checkClosed();
                mCloseableSet.put(c, "");
            }
        } catch (IOException e) {
            try {
                c.close();
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        }
    }

    ChannelAcceptor newChannelAcceptor(SocketAddress localAddress) throws IOException {
        RecyclableSocketChannelSelector selector = mSelector;
        if (selector != null) {
            return selector.newChannelAcceptor(localAddress);
        }

        ServerSocketFactory ssf = mServerSocketFactory;
        if (ssf == null) {
            ssf = ServerSocketFactory.getDefault();
        }
        
        ServerSocket ss = ssf.createServerSocket();
        if (mRecyclableSockets) {
            return new RecyclableSocketChannelAcceptor(mIOExecutor, localAddress, ss);
        } else {
            return new BufferedSocketChannelAcceptor(mIOExecutor, localAddress, ss);
        }
    }

    ChannelConnector newChannelConnector(SocketAddress remoteAddress, SocketAddress localAddress) {
        RecyclableSocketChannelSelector selector = mSelector;
        if (selector != null) {
            return selector.newChannelConnector(remoteAddress, localAddress);
        }

        SocketFactory sf = mSocketFactory;
        if (sf == null) {
            sf = SocketFactory.getDefault();
        }

        if (mRecyclableSockets) {
            return new RecyclableSocketChannelConnector
                (mIOExecutor, remoteAddress, localAddress, sf);
        } else {
            return new BufferedSocketChannelConnector
                (mIOExecutor, remoteAddress, localAddress, sf);
        }
    }

    private class SocketConnector implements SessionConnector {
        private final ChannelConnector mChannelConnector;
        private final ChannelBrokerConnector mBrokerConnector;

        SocketConnector(SocketAddress remoteAddress, SocketAddress localAddress) {
            if (remoteAddress == null) {
                throw new IllegalArgumentException("Must provide a remote address");
            }
            mChannelConnector = newChannelConnector(remoteAddress, localAddress);
            mBrokerConnector = new BasicChannelBrokerConnector(mIOExecutor, mChannelConnector);
        }

        public Session connect() throws IOException {
            checkClosed();
            ChannelBroker broker = mBrokerConnector.connect();
            addToClosableSet(broker);
            try {
                return newSession(broker);
            } catch (IOException e) {
                broker.close();
                throw e;
            }
        }

        public Session connect(long timeout, TimeUnit unit) throws IOException {
            if (timeout < 0) {
                return connect();
            }
            checkClosed();
            Timer timer = new Timer(timeout, unit);
            ChannelBroker broker = mBrokerConnector.connect(timer);
            addToClosableSet(broker);
            try {
                return newSession(broker, timer);
            } catch (IOException e) {
                broker.close();
                throw e;
            }
        }

        public Object getRemoteAddress() {
            return mChannelConnector.getRemoteAddress();
        }

        public Object getLocalAddress() {
            return mChannelConnector.getLocalAddress();
        }

        @Override
        public String toString() {
            String str = "SessionConnector {remoteAddress=" + getRemoteAddress();
            Object localAddress = getLocalAddress();
            if (localAddress != null) {
                str += ", localAddress=" + localAddress;
            }
            return str + '}';
        }
    }
}
