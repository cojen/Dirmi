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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Connector;
import org.cojen.dirmi.Environment;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class Engine implements Environment {
    private final Lock mMainLock;

    private final Executor mExecutor;
    private final boolean mOwnsExecutor;

    private final Lock mSchedulerLock;
    private final Condition mSchedulerCondition;
    private final PriorityQueue<Scheduled> mScheduledQueue;
    private boolean mSchedulerRunning;

    private volatile ItemMap<ServerSession> mServerSessions;
    private volatile ItemMap<ClientSession> mClientSessions;

    private volatile ConcurrentSkipListMap<byte[], Object> mExports;

    private Map<Object, Acceptor> mAcceptors;

    private Connector mConnector;

    private static final VarHandle cConnectorHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cConnectorHandle = lookup.findVarHandle(Engine.class, "mConnector", Connector.class);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    public Engine() {
        this(null, true);
    }

    public Engine(Executor executor) {
        this(executor, false);
    }

    private Engine(Executor executor, boolean ownsExecutor) {
        mMainLock = new ReentrantLock();

        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        mExecutor = executor;
        mOwnsExecutor = ownsExecutor;

        mSchedulerLock = new ReentrantLock();
        mSchedulerCondition = mSchedulerLock.newCondition();
        mScheduledQueue = new PriorityQueue<>();

        cConnectorHandle.setRelease(this, Connector.direct());
    }

    @Override
    public Object export(Object name, Object obj) throws IOException {
        byte[] bname = binaryName(name);

        if (obj != null) {
            // Validate the remote object.
            RemoteExaminer.remoteType(obj);
        }

        mMainLock.lock();
        try {
            checkClosed();
            var exports = mExports;
            if (exports == null) {
                mExports = exports = new ConcurrentSkipListMap<>(Arrays::compare);
            }
            return obj == null ? exports.remove(bname) : exports.put(bname, obj);
        } finally {
            mMainLock.unlock();
        }
    }

    public Closeable acceptAll(ServerSocket ss) throws IOException {
        if (!ss.isBound()) {
            throw new IllegalStateException("Socket isn't bound");
        }
        return acceptAll(ss, new SocketAcceptor(ss));
    }

    public Closeable acceptAll(ServerSocketChannel ss) throws IOException {
        if (ss.getLocalAddress() == null) {
            throw new IllegalStateException("Socket isn't bound");
        }
        return acceptAll(ss, new ChannelAcceptor(ss));
    }

    private Closeable acceptAll(Object ss, Acceptor acceptor) throws IOException {
        mMainLock.lock();
        try {
            checkClosed();
            if (mAcceptors == null) {
                mAcceptors = new IdentityHashMap<>();
            }
            if (mAcceptors.putIfAbsent(ss, acceptor) != null) {
                throw new IllegalStateException("Already accepting connections from " + ss);
            }
        } finally {
            mMainLock.unlock();
        }

        execute(acceptor);

        return acceptor;
    }

    private void unregister(Object ss, Acceptor acceptor) {
        mMainLock.lock();
        try {
            if (mAcceptors != null) {
                mAcceptors.remove(ss, acceptor);
            }
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public Session<?> accepted(SocketAddress localAddr, SocketAddress remoteAttr,
                               InputStream in, OutputStream out)
        throws IOException
    {
        var pipe = new CorePipe(localAddr, remoteAttr, in, out);

        long clientSessionId = 0;

        try {
            long version = pipe.readLong();
            if (version != CoreUtils.PROTOCOL_V2 || (clientSessionId = pipe.readLong()) == 0) {
                throw new RemoteException("Unsupported protocol");
            }

            long serverSessionId = pipe.readLong();

            if (serverSessionId != 0) {
                ItemMap<ServerSession> sessions = mServerSessions;
                if (sessions == null) {
                    checkClosed();
                }
                ServerSession session = sessions.get(serverSessionId);
                if (session == null) {
                    throw new RemoteException("Unable to find existing session");
                }
                session.accepted(pipe);
                return session;
            }

            Object name = pipe.readObject();
            var clientInfo = (RemoteInfo) pipe.readObject();
            Object metadata = pipe.readObject();

            var exports = mExports;
            Object root;
            if (exports == null || (root = exports.get(binaryName(name))) == null) {
                throw new RemoteException("Unable to find exported object");
            }

            Class<?> rootType = RemoteExaminer.remoteType(root);
            String rootTypeName = rootType.getName();

            if (!clientInfo.name().equals(rootTypeName)) {
                throw new RemoteException("Mismatched root object type");
            }

            RemoteInfo serverInfo = RemoteInfo.examine(rootType);

            var session = new ServerSession<Object>(this, root, clientInfo);
            session.registerNewConnection(pipe);

            session.writeHeader(pipe, clientSessionId);
            pipe.writeObject(serverInfo);
            pipe.writeObject((Object) null); // optional metadata
            pipe.flush();

            mMainLock.lock();
            try {
                checkClosed();
                ItemMap<ServerSession> sessions = mServerSessions;
                if (sessions == null) {
                    mServerSessions = sessions = new ItemMap<>();
                }
                sessions.put(session);
            } finally {
                mMainLock.unlock();
            }

            // FIXME: start a task to keep reading the control connection

            return session;
        } catch (RemoteException e) {
            if (clientSessionId != 0) {
                // FIXME: launch a task to force close after a timeout elapses
                pipe.writeLong(CoreUtils.PROTOCOL_V2);
                pipe.writeLong(clientSessionId);
                pipe.writeLong(0); // server session id of zero indicates an error
                pipe.writeObject(e.getMessage());
                pipe.flush();
            }
            CoreUtils.closeQuietly(pipe);
            throw e;
        } catch (Throwable e) {
            CoreUtils.closeQuietly(pipe);
            throw e;
        }
    }

    @Override
    public <R> Session<R> connect(Class<R> type, Object name, SocketAddress addr)
        throws IOException
    {
        RemoteInfo info = RemoteInfo.examine(type);
        byte[] bname = binaryName(name);

        var session = new ClientSession<R>(this, addr);
        CorePipe pipe = session.connect();

        try {
            session.registerNewConnection(pipe);

            session.writeHeader(pipe, 0); // server session of zero creates a new session
            pipe.write(bname); // root object name
            pipe.writeObject(info); // root object type
            pipe.writeObject((Object) null); // optional metadata
            pipe.flush();

            long version = pipe.readLong();
            if (version != CoreUtils.PROTOCOL_V2 || pipe.readLong() != session.id) {
                throw new RemoteException("Unsupported protocol");
            }

            long serverSessionId = pipe.readLong();

            if (serverSessionId == 0) {
                // Connection was rejected.
                Object message = pipe.readObject();
                throw new RemoteException(String.valueOf(message));
            }

            long rootId = pipe.readLong();
            long rootTypeId = pipe.readLong();
            var serverInfo = (RemoteInfo) pipe.readObject();
            Object metadata = pipe.readObject();

            session.init(serverSessionId, type, serverInfo, rootId, rootTypeId);

            mMainLock.lock();
            try {
                checkClosed();
                ItemMap<ClientSession> sessions = mClientSessions;
                if (sessions == null) {
                    mClientSessions = sessions = new ItemMap<>();
                }
                sessions.put(session);
            } finally {
                mMainLock.unlock();
            }

            // FIXME: start a task to keep reading the control connection

            return session;
        } catch (Throwable e) {
            session.close();
            CoreUtils.closeQuietly(pipe);
            throw e;
        }
    }

    @Override
    public Connector connector(Connector c) throws IOException {
        mMainLock.lock();
        try {
            Objects.requireNonNull(c);
            Connector old = checkClosed();
            cConnectorHandle.setRelease(this, Connector.direct());
            return old;
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public void close() {
        ItemMap<ServerSession> serverSessions;
        ItemMap<ClientSession> clientSessions;

        Map<Object, Acceptor> acceptors;

        mMainLock.lock();
        try {
            if (mConnector == null) {
                return;
            }

            cConnectorHandle.setRelease(this, (Connector) null);

            serverSessions = mServerSessions;
            mServerSessions = null;
            clientSessions = mClientSessions;
            mClientSessions = null;

            mExports = null;

            acceptors = mAcceptors;
            mAcceptors = null;
        } finally {
            mMainLock.unlock();
        }

        mSchedulerLock.lock();
        try {
            mScheduledQueue.clear();
            mSchedulerCondition.signal();
        } finally {
            mSchedulerLock.unlock();
        }

        if (acceptors != null) {
            for (Acceptor acceptor : acceptors.values()) {
                CoreUtils.closeQuietly(acceptor);
            }
        }

        closeAll(serverSessions);
        closeAll(clientSessions);

        if (mOwnsExecutor && mExecutor instanceof ExecutorService) {
            ((ExecutorService) mExecutor).shutdown();
        }
    }

    private static void closeAll(ItemMap<? extends CoreSession> sessions) {
        if (sessions != null) {
            sessions.forEach(CoreSession::close);
        }
    }

    /**
     * Attempt to execute the task in a separate thread. If an exception is thrown from this
     * method and the task also implements Closeable, then the task is closed.
     */
    void execute(Runnable task) throws IOException {
        try {
            mExecutor.execute(task);
        } catch (Throwable e) {
            if (task instanceof Closeable) {
                CoreUtils.closeQuietly((Closeable) task);
            }
            checkClosed();
            throw new RemoteException(e);
        }
    }

    boolean scheduleMillis(Scheduled task, long delayMillis) throws IOException {
        return scheduleNanos(task, delayMillis * 1_000_000L);
    }

    boolean scheduleNanos(Scheduled task, long delayNanos) throws IOException {
        task.mAtNanos = System.nanoTime() + delayNanos;

        mSchedulerLock.lock();
        try {
            if (isClosed()) {
                return false;
            }

            mScheduledQueue.add(task);
            
            if (!mSchedulerRunning) {
                execute(this::runScheduledTasks);
                mSchedulerRunning = true;
            } else if (mScheduledQueue.peek() == task) {
                mSchedulerCondition.signal();
            }

            return true;
        } finally {
            mSchedulerLock.unlock();
        }
    }

    private void runScheduledTasks() {
        while (true) {
            Scheduled task;

            mSchedulerLock.lock();
            try {
                while (true) {
                    task = mScheduledQueue.peek();
                    if (task == null) {
                        mSchedulerRunning = false;
                        return;
                    }
                    long delayNanos = task.mAtNanos - System.nanoTime();
                    if (delayNanos <= 0) {
                        break;
                    }
                    try {
                        mSchedulerCondition.awaitNanos(delayNanos);
                    } catch (InterruptedException e) {
                        // Check if closed at the start of the loop.
                    }
                }

                if (task != mScheduledQueue.remove()) {
                    throw new AssertionError();
                }
            } catch (Throwable e) {
                mSchedulerRunning = false;
                throw e;
            } finally {
                mSchedulerLock.unlock();
            }

            try {
                mExecutor.execute(task);
            } catch (Throwable e) {
                if (task instanceof Closeable) {
                    CoreUtils.closeQuietly((Closeable) task);
                }
                // FIXME: log it?
                CoreUtils.uncaughtException(e);
                return;
            }
        }
    }

    Connector checkClosed() throws IOException {
        var c = (Connector) cConnectorHandle.getAcquire(this);
        if (c == null) {
            throw new ClosedException("Environment is closed");
        }
        return c;
    }

    boolean isClosed() {
        return cConnectorHandle.getAcquire(this) == null;
    }

    /**
     * Called by Acceptor.
     */
    private void acceptFailed(Throwable e) {
        // FIXME: log it?
        CoreUtils.uncaughtException(e);
    }

    private static byte[] binaryName(Object name) {
        var capture = new OutputStream() {
            private byte[] mBytes;
            private ByteArrayOutputStream mBout;

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b});
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (mBout == null) {
                    if (mBytes == null) {
                        var bytes = new byte[len];
                        System.arraycopy(b, off, bytes, 0, len);
                        mBytes = bytes;
                        return;
                    }
                    mBout = new ByteArrayOutputStream();
                    mBout.write(mBytes);
                    mBytes = null;
                }

                mBout.write(b, off, len);
            }

            byte[] bytes() {
                return mBout == null ? mBytes : mBout.toByteArray();
            }
        };

        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        try {
            pipe.writeObject(name);
            pipe.flush();
            return capture.bytes();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final VarHandle cStateHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cStateHandle = lookup.findVarHandle(Acceptor.class, "mState", int.class);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    private abstract class Acceptor implements Closeable, Runnable {
        private volatile int mState;

        protected final boolean start() {
            return cStateHandle.compareAndSet(this, 0, 1);
        }

        protected final boolean isClosed() {
            if (mState < 0) {
                return true;
            }
            if (Engine.this.isClosed()) {
                CoreUtils.closeQuietly(this);
                return true;
            }
            return false;
        }

        protected final void doClose(Closeable ss) {
            mState = -1;
            CoreUtils.closeQuietly(ss);
            unregister(ss, this);
        }
    }

    private final class SocketAcceptor extends Acceptor {
        private final ServerSocket mSocket;

        SocketAcceptor(ServerSocket socket) {
            mSocket = socket;
        }

        @Override
        public void run() {
            if (!start()) {
                return;
            }

            while (!isClosed()) {
                Socket s = null;
                try {
                    s = mSocket.accept();
                    accepted(s);
                } catch (Throwable e) {
                    CoreUtils.closeQuietly(s);
                    if (isClosed()) {
                        return;
                    }
                    acceptFailed(e);
                    if (mSocket.isClosed()) {
                        close();
                        return;
                    }
                    Thread.yield();
                }
            }
        }

        @Override
        public void close() {
            doClose(mSocket);
        }
    }

    private final class ChannelAcceptor extends Acceptor {
        private final ServerSocketChannel mChannel;

        ChannelAcceptor(ServerSocketChannel channel) throws IOException {
            mChannel = channel;
            channel.configureBlocking(true);
        }

        @Override
        public void run() {
            if (!start()) {
                return;
            }

            while (!isClosed()) {
                SocketChannel s = null;
                try {
                    s = mChannel.accept();
                    accepted(s);
                } catch (Throwable e) {
                    CoreUtils.closeQuietly(s);
                    if (isClosed()) {
                        return;
                    }
                    acceptFailed(e);
                    if (!mChannel.isOpen()) {
                        close();
                        return;
                    }
                    Thread.yield();
                }
            }
        }

        @Override
        public void close() {
            doClose(mChannel);
        }
    }
}