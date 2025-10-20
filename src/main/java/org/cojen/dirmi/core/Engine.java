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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import java.util.function.Predicate;

import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.cojen.dirmi.ClassResolver;
import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Connector;
import org.cojen.dirmi.Environment;
import org.cojen.dirmi.NoSuchObjectException;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Serializer;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.SessionAware;

import org.cojen.dirmi.io.CaptureOutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class Engine implements Environment {
    private final Lock mMainLock;

    private volatile Settings mSettings;

    private final Executor mExecutor;
    private final boolean mCloseExecutor;

    private final Lock mSchedulerLock;
    private final Condition mSchedulerCondition;
    private final PriorityQueue<Scheduled> mScheduledQueue;
    private boolean mSchedulerRunning;

    private volatile ItemMap<ServerSession> mServerSessions;
    private volatile ItemMap<ClientSession> mClientSessions;

    private volatile ConcurrentSkipListMap<byte[], Object> mExports;

    private Map<Object, Acceptor> mAcceptors;

    private volatile BiConsumer<Session<?>, Throwable> mUncaughtExceptionHandler;

    private Connector mConnector;

    private static final VarHandle cConnectorHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cConnectorHandle = lookup.findVarHandle(Engine.class, "mConnector", Connector.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }
    }

    /**
     * @param executor pass null to use a default
     */
    public Engine(Executor executor, boolean closeExecutor) {
        mMainLock = new ReentrantLock();

        mSettings = new Settings();

        if (executor == null) {
            executor = Executors.newCachedThreadPool(TFactory.THE);
        } else if (closeExecutor) {
            if (!(executor instanceof Closeable) && !(executor instanceof ExecutorService)) {
                throw new IllegalArgumentException();
            }
        }

        mExecutor = executor;
        mCloseExecutor = closeExecutor;

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
            RemoteInfo.examine(RemoteExaminer.remoteType(obj));
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

    public Closeable acceptAll(ServerSocket ss, Predicate<Socket> listener) throws IOException {
        if (!ss.isBound()) {
            throw new IllegalStateException("Socket isn't bound");
        }
        return acceptAll(ss, new SocketAcceptor(ss, listener));
    }

    public Closeable acceptAll(ServerSocketChannel ss, Predicate<SocketChannel> listener)
        throws IOException
    {
        if (ss.getLocalAddress() == null) {
            throw new IllegalStateException("Socket isn't bound");
        }
        return acceptAll(ss, new ChannelAcceptor(ss, listener));
    }

    @SuppressWarnings("deprecation")
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

        try {
            Thread t = new Thread(acceptor);
            t.setName("DirmiAcceptor-" + t.getId());
            t.start();
        } catch (Throwable e) {
            acceptor.close();
            throw e;
        }

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
    public Session<?> accepted(SocketAddress localAddr, SocketAddress remoteAddr,
                               InputStream in, OutputStream out)
        throws IOException
    {
        checkClosed();

        var pipe = new CorePipe(localAddr, remoteAddr, in, out, CorePipe.M_SERVER);

        var settings = mSettings;
        int timeoutMillis = settings.pingTimeoutMillis;
        CloseTimeout timeoutTask = timeoutMillis < 0 ? null : new CloseTimeout(pipe);

        long clientSessionId = 0;
        ServerSession session;
        RemoteInfo serverInfo;

        int firstCustomTypeCode;
        LinkedHashMap<String, Object> clientCustomTypes = null;

        try {
            if (timeoutTask != null) {
                scheduleMillis(timeoutTask, timeoutMillis);
            }

            long version = pipe.readLong();
            if (version != CoreUtils.PROTOCOL_V2 || (clientSessionId = pipe.readLong()) == 0) {
                throw new RemoteException("Unsupported protocol");
            }

            long serverSessionId = pipe.readLong();

            if (serverSessionId != 0) {
                CloseTimeout.cancelOrFail(timeoutTask);

                find: {
                    ItemMap<ServerSession> sessions = mServerSessions;
                    if (sessions != null) {
                        try {
                            session = sessions.get(serverSessionId);
                            break find;
                        } catch (NoSuchObjectException e) {
                        }
                    }
                    checkClosed();
                    throw new RemoteException("Unable to find existing session");
                }

                // Capture the clientSessionId and unset it before writing to the pipe, in case
                // a RemoteException is thrown. This prevents the exception handler below from
                // writing the header to the pipe a second time.
                long csid = clientSessionId;
                clientSessionId = 0;

                pipe.writeLong(CoreUtils.PROTOCOL_V2);
                pipe.writeLong(csid);
                pipe.writeLong(serverSessionId);
                pipe.flush();

                session.accepted(pipe);
                return session;
            }

            Object name = pipe.readObject();
            RemoteInfo clientInfo = RemoteInfo.readFrom(pipe);

            firstCustomTypeCode = pipe.readUnsignedByte();
            if (firstCustomTypeCode != 0) {
                clientCustomTypes = Settings.readSerializerTypes(pipe);
            }

            Object metadata = pipe.readObject();

            var exports = mExports;
            Object root;
            if (exports == null || (root = exports.get(binaryName(name))) == null) {
                throw new RemoteException("Unable to find exported object");
            }

            Class<?> rootType = RemoteExaminer.remoteType(root);
            serverInfo = RemoteInfo.examine(rootType);

            if (!clientInfo.isAssignableFrom(serverInfo)) {
                throw new RemoteException("Mismatched root object type");
            }

            session = new ServerSession<Object>(this, settings, root, pipe);
        } catch (Throwable e) {
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }

            try {
                if (e instanceof RemoteException re) {
                    // Capture the message before the remote address is appended to it.
                    String message = re.getMessage();

                    re.remoteAddress(remoteAddr);

                    if (clientSessionId != 0) {
                        pipe.writeLong(CoreUtils.PROTOCOL_V2);
                        pipe.writeLong(clientSessionId);
                        pipe.writeLong(0); // server session id of zero indicates an error
                        pipe.writeObject(message);
                        pipe.flush();
                    }
                }

                pipe.close();
            } catch (Throwable e2) {
                // Ignore.
            }

            throw e;
        }

        session.mStateLock.lock();

        try {
            mMainLock.lock();
            try {
                checkClosed();
                ItemMap<ServerSession> sessions = mServerSessions;
                if (sessions == null) {
                    mServerSessions = sessions = new ItemMap<>();
                }
                sessions.putUnique(session);
            } finally {
                mMainLock.unlock();
            }

            session.writeHeader(pipe, clientSessionId);
            serverInfo.writeTo(pipe);

            LinkedHashMap<String, Object> serverCustomTypes = settings.writeSerializerTypes(pipe);
            pipe.writeNull(); // optional metadata
            pipe.flush();

            TypeCodeMap tcm = settings.mergeSerializerTypes
                (firstCustomTypeCode, serverCustomTypes, clientCustomTypes);

            session.initTypeCodeMap(tcm);

            // Assign the state after the call to initTypeCodeMap, to prevent race conditions
            // when a new connection is accepted too soon. See ServerSession.accepted.
            session.mState = Session.State.CONNECTED;

            session.writeRootDataFields(pipe);
            pipe.flush();

            CloseTimeout.cancelOrFail(timeoutTask);

            session.controlPipe(pipe);
            session.startTasks();

            if (session.root() instanceof SessionAware sa) {
                // The root can be notified of attachment after the session is fully connected.
                session.attachNotify(sa);
            }

            return session;
        } catch (Throwable e) {
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }
            CoreUtils.closeQuietly(session);
            if (e instanceof RemoteException re) {
                re.remoteAddress(remoteAddr);
            }
            throw e;
        } finally {
            session.mStateLock.unlock();
        }
    }

    @Override
    public <R> Session<R> connect(Class<R> type, Object name, SocketAddress addr)
        throws IOException
    {
        checkClosed();
        return doConnect(mSettings, true, type, binaryName(name), addr);
    }

    /**
     * @return register when false, don't register the new session or start tasks
     */
    <R> ClientSession<R> doConnect(Settings settings, boolean register,
                                   Class<R> type, byte[] bname, SocketAddress addr)
        throws IOException
    {
        checkClosed();

        RemoteInfo info = RemoteInfo.examine(type);

        var session = new ClientSession<R>(this, settings, null, addr);
        CorePipe pipe = session.connect();

        int timeoutMillis = settings.pingTimeoutMillis;
        CloseTimeout timeoutTask = timeoutMillis < 0 ? null : new CloseTimeout(pipe);

        int firstCustomTypeCode;
        LinkedHashMap<String, Object> clientCustomTypes;
        LinkedHashMap<String, Object> serverCustomTypes = null;

        try {
            if (timeoutTask != null) {
                scheduleMillis(timeoutTask, timeoutMillis);
            }

            session.writeHeader(pipe, 0); // server session of zero creates a new session
            pipe.write(bname); // root object name
            info.writeTo(pipe);  // root object type
            clientCustomTypes = settings.writeSerializerTypes(pipe);
            pipe.writeNull(); // optional metadata
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
            RemoteInfo serverInfo = RemoteInfo.readFrom(pipe);

            firstCustomTypeCode = pipe.readUnsignedByte();
            if (firstCustomTypeCode != 0) {
                serverCustomTypes = Settings.readSerializerTypes(pipe);
            }

            Object metadata = pipe.readObject();

            session.init(serverSessionId, type, bname, rootTypeId, serverInfo, rootId);

            TypeCodeMap tcm = settings.mergeSerializerTypes
                (firstCustomTypeCode, clientCustomTypes, serverCustomTypes);

            session.initTypeCodeMap(tcm);

            session.readRootDataFields(pipe);

            CloseTimeout.cancelOrFail(timeoutTask);

            session.controlPipe(pipe);

            if (register) {
                session.startTasks();

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
            }

            return session;
        } catch (Throwable e) {
            try {
                CloseTimeout.cancelOrFail(timeoutTask);
            } catch (Throwable e2) {
                e2.addSuppressed(e);
                e = e2;
            }
            session.close();
            CoreUtils.closeQuietly(pipe);
            if (e instanceof RemoteException re) {
                re.remoteAddress(addr);
            }
            throw CoreUtils.rethrow(e);
        }
    }

    /**
     * Start a reconnect task. When reconnect succeeds, the given callback receives the
     * ClientSession instance, which isn't registered and no tasks have started. When a
     * reconnect attempt fails, the callback is given an exception, and it can return false to
     * stop the task.
     *
     * @param callback receives a ClientSession instance or an exception
     */
    void reconnect(Settings settings, Class<?> type, byte[] bname, SocketAddress addr,
                   Predicate<Object> callback)
    {
        var task = new Scheduled() {
            private final Random mRnd = new Random();

            @Override
            public void run() {
                ClientSession<?> session = null;
                Object result;
                
                try {
                    session = doConnect(settings, false, type, bname, addr);
                    result = session;
                } catch (Throwable e) {
                    try {
                        checkClosed();
                    } catch (Throwable e2) {
                        // Is closed, so don't attempt again.
                        callback.test(e2);
                        return;
                    }
                    result = e;
                }

                try {
                    if (!callback.test(result)) {
                        return;
                    }
                } catch (Throwable e) {
                    CoreUtils.closeQuietly(session);
                    throw e;
                }

                try {
                    schedule();
                } catch (Throwable e) {
                    // Can't schedule tasks for some reason, so don't attempt again.
                    callback.test(e);
                }
            }

            void schedule() throws IOException {
                long delay = settings.reconnectDelayMillis;
                long range = delay / 5;
                if (range > 1) {
                    // Adjust delay by ±10%.
                    long jitter = mRnd.nextLong(range);
                    jitter -= delay / 10;
                    delay += jitter;
                }

                if (!scheduleMillis(this, delay)) {
                    checkClosed();
                    // Not expected.
                    executeTask(this);
                }
            }
        };

        try {
            task.schedule();
        } catch (Throwable e) {
            // Can't schedule tasks for some reason, so don't attempt again.
            callback.test(e);
        }
    }

    @Override
    public Connector connector(Connector c) throws IOException {
        mMainLock.lock();
        try {
            Objects.requireNonNull(c);
            Connector old = checkClosed();
            cConnectorHandle.setRelease(this, c);
            return old;
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public void customSerializers(Serializer... serializers) {
        LinkedHashMap<Class<?>, Serializer> map;

        if (serializers == null || serializers.length == 0) {
            map = null;
        } else {
            map = new LinkedHashMap<>(serializers.length);

            for (var serializer : serializers) {
                Set<Class<?>> types = serializer.supportedTypes();
                for (Class<?> type : types) {
                    Objects.requireNonNull(type);
                    map.putIfAbsent(type, serializer);
                }
            }
        }

        mMainLock.lock();
        try {
            mSettings = mSettings.withSerializers(map);
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public void reconnectDelayMillis(int millis) {
        mMainLock.lock();
        try {
            mSettings = mSettings.withReconnectDelayMillis(millis);
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public void pingTimeoutMillis(int millis) {
        mMainLock.lock();
        try {
            mSettings = mSettings.withPingTimeoutMillis(millis);
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public void idleConnectionMillis(int millis) {
        mMainLock.lock();
        try {
            mSettings = mSettings.withIdleConnectionMillis(millis);
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public void classResolver(ClassResolver resolver) {
        mMainLock.lock();
        try {
            mSettings = mSettings.withClassResolver(resolver);
        } finally {
            mMainLock.unlock();
        }
    }

    @Override
    public void uncaughtExceptionHandler(BiConsumer<Session<?>, Throwable> h) {
        mUncaughtExceptionHandler = h;
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

        if (mCloseExecutor) {
            if (mExecutor instanceof ExecutorService es) {
                es.shutdown();
            } else if (mExecutor instanceof Closeable c) {
                CoreUtils.closeQuietly(c);
            }
        }
    }

    private static void closeAll(ItemMap<? extends CoreSession> sessions) {
        if (sessions != null) {
            sessions.forEach(CoreSession::close);
        }
    }

    void removeSession(ServerSession session) {
        ItemMap<ServerSession> sessions = mServerSessions;
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    void removeSession(ClientSession session) {
        ItemMap<ClientSession> sessions = mClientSessions;
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    void changeIdentity(ClientSession session, long newSessionId) {
        ItemMap<ClientSession> sessions = mClientSessions;
        if (sessions != null) {
            sessions.changeIdentity(session, newSessionId);
        }
    }

    @Override
    public void execute(Runnable command) {
        mExecutor.execute(command);
    }

    /**
     * Attempt to execute the task in a separate thread. If an exception is thrown from this
     * method and the task also implements Closeable, then the task is closed.
     */
    void executeTask(Runnable task) throws IOException {
        try {
            mExecutor.execute(task);
        } catch (Throwable e) {
            if (task instanceof Closeable c) {
                CoreUtils.closeQuietly(c);
            }
            checkClosed();
            throw new RemoteException(e);
        }
    }

    /**
     * Attempt to execute the task in a separate thread. If unable and the task also implements
     * Closeable, then the task is closed.
     */
    boolean tryExecuteTask(Runnable task) {
        try {
            mExecutor.execute(task);
            return true;
        } catch (Throwable e) {
            if (task instanceof Closeable c) {
                CoreUtils.closeQuietly(c);
            }
            return false;
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
                executeTask(this::runScheduledTasks);
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
                if (task instanceof Closeable c) {
                    CoreUtils.closeQuietly(c);
                }
                if (!isClosed()) {
                    uncaught(null, e);
                }
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

    void uncaught(Session<?> s, Throwable e) {
        if (!CoreUtils.acceptException(mUncaughtExceptionHandler, s, e)) {
            try {
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
            } catch (Throwable e2) {
                // Ignore.
            }
        }
    }

    /**
     * Called by Acceptor.
     */
    private void acceptFailed(Throwable e) {
        if (!isClosed()) {
            uncaught(null, e);
        }
    }

    private static byte[] binaryName(Object name) {
        try {
            var capture = new CaptureOutputStream();
            var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
            pipe.writeObject(name);
            pipe.flush();
            return capture.getBytes();
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
            throw CoreUtils.rethrow(e);
        }
    }

    private static final class TFactory implements ThreadFactory {
        static final TFactory THE = new TFactory();

        @Override
        @SuppressWarnings("deprecation")
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Dirmi-" + t.getId());
            return t;
        }
    }

    private abstract class Acceptor implements Closeable, Runnable {
        private volatile int mState;

        final boolean start() {
            return cStateHandle.compareAndSet(this, 0, 1);
        }

        final boolean isClosed() {
            if (mState < 0) {
                return true;
            }
            if (Engine.this.isClosed()) {
                CoreUtils.closeQuietly(this);
                return true;
            }
            return false;
        }

        final void doClose(Closeable ss) {
            mState = -1;
            unregister(ss, this);
            CoreUtils.closeQuietly(ss);
        }
    }

    private final class SocketAcceptor extends Acceptor {
        private final ServerSocket mSocket;
        private final Predicate<Socket> mListener;

        SocketAcceptor(ServerSocket socket, Predicate<Socket> listener) {
            mSocket = socket;
            mListener = listener;
        }

        @Override
        public void run() {
            if (!start()) {
                return;
            }

            while (!isClosed()) {
                try {
                    Socket s = mSocket.accept();
                    acceptedTask(s);
                    s = null;
                } catch (Throwable e) {
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

        private void acceptedTask(Socket s) throws IOException {
            executeTask(() -> {
                try {
                    if (mListener == null || mListener.test(s)) {
                        accepted(s);
                    } else {
                        CoreUtils.closeQuietly(s);
                    }
                } catch (Throwable e) {
                    CoreUtils.closeQuietly(s);
                    if (!(e instanceof IOException)) {
                        acceptFailed(e);
                    }
                }
            });
        }

        @Override
        public void close() {
            doClose(mSocket);
        }
    }

    private final class ChannelAcceptor extends Acceptor {
        private final ServerSocketChannel mChannel;
        private final Predicate<SocketChannel> mListener;

        ChannelAcceptor(ServerSocketChannel channel, Predicate<SocketChannel> listener)
            throws IOException
        {
            mChannel = channel;
            channel.configureBlocking(true);
            mListener = listener;
        }

        @Override
        public void run() {
            if (!start()) {
                return;
            }

            while (!isClosed()) {
                try {
                    SocketChannel s = mChannel.accept();
                    acceptedTask(s);
                    s = null;
                } catch (Throwable e) {
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

        private void acceptedTask(SocketChannel s) throws IOException {
            executeTask(() -> {
                try {
                    if (mListener == null || mListener.test(s)) {
                        accepted(s);
                    } else {
                        CoreUtils.closeQuietly(s);
                    }
                } catch (Throwable e) {
                    CoreUtils.closeQuietly(s);
                    if (!(e instanceof IOException)) {
                        acceptFailed(e);
                    }
                }
            });
        }

        @Override
        public void close() {
            doClose(mChannel);
        }
    }
}
