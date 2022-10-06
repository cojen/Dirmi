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
import java.io.IOException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.lang.ref.WeakReference;

import java.net.SocketAddress;

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import java.util.function.BiConsumer;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.DisconnectedException;
import org.cojen.dirmi.NoSuchObjectException;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.SessionAware;

/**
 * Base class for ClientSession and ServerSession.
 *
 * @author Brian S O'Neill
 */
abstract class CoreSession<R> extends Item implements Session<R> {
    // Control commands.
    static final int
        C_PING = 1, C_PONG = 2, C_MESSAGE = 3, C_KNOWN_TYPE = 4, C_REQUEST_CONNECTION = 5,
        C_REQUEST_INFO = 6, C_REQUEST_INFO_TERM = 7, C_INFO_FOUND = 8, C_INFO_NOT_FOUND = 9;

    static final int CLOSED = 1, DISCONNECTED = 2, PING_FAILURE = 4, CONTROL_FAILURE = 8;

    private static final int SPIN_LIMIT;

    private static final VarHandle cStubSupportHandle,
        cControlPipeHandle, cConLockHandle, cPipeClockHandle;

    static {
        SPIN_LIMIT = Runtime.getRuntime().availableProcessors() > 1 ? 1 << 10 : 1;

        try {
            var lookup = MethodHandles.lookup();
            cStubSupportHandle = lookup.findVarHandle
                (CoreSession.class, "mStubSupport", CoreStubSupport.class);
            cControlPipeHandle = lookup.findVarHandle
                (CoreSession.class, "mControlPipe", CorePipe.class);
            cConLockHandle = lookup.findVarHandle(CoreSession.class, "mConLock", int.class);
            cPipeClockHandle = lookup.findVarHandle(CorePipe.class, "mClock", int.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }
    }

    final Engine mEngine;
    final Settings mSettings;
    final ItemMap<Stub> mStubs;
    final ItemMap<StubFactory> mStubFactories;
    final ConcurrentHashMap<Class<?>, StubFactory> mStubFactoriesByClass;
    final SkeletonMap mSkeletons;
    final ItemMap<Item> mKnownTypes; // tracks types known by the remote client

    private CoreStubSupport mStubSupport;
    final CoreSkeletonSupport mSkeletonSupport;

    // Acquire this lock when writing commands over the control pipe.
    final Lock mControlLock;
    private CorePipe mControlPipe;

    TypeCodeMap mTypeCodeMap;

    private volatile int mConLock;

    // Linked list of connections. Connections which range from first to before avail are
    // currently being used. Connections which range from avail to last are waiting to be used.
    private CorePipe mConFirst, mConAvail, mConLast;

    private int mConClock;

    private volatile BiConsumer<Session<?>, Throwable> mUncaughtExceptionHandler;

    private int mClosed;

    // Used when reconnecting.
    volatile WaitMap<String, RemoteInfo> mTypeWaitMap;

    CoreSession(Engine engine, Settings settings) {
        super(IdGenerator.next());
        mEngine = engine;
        mSettings = settings;
        mStubs = new ItemMap<Stub>();
        mStubFactories = new ItemMap<StubFactory>();
        mStubFactoriesByClass = new ConcurrentHashMap<>();
        mSkeletons = new SkeletonMap(this);
        mKnownTypes = new ItemMap<>();

        stubSupport(new CoreStubSupport(this));
        mSkeletonSupport = new CoreSkeletonSupport(this);

        mControlLock = new ReentrantLock();
    }

    /**
     * Must call this method before a new session is ready.
     */
    final void initTypeCodeMap(TypeCodeMap tcm) {
        mTypeCodeMap = tcm;
        VarHandle.storeStoreFence();
    }

    /**
     * Track a new connection as being immediately used (not available for other uses).
     * If an exception is thrown, the pipe is closed as a side effect.
     */
    final void registerNewConnection(CorePipe pipe) throws RemoteException {
        conLockAcquire();
        try {
            checkClosed();
            pipe.mSession = this;

            CorePipe first = mConFirst;
            if (first == null) {
                mConLast = pipe;
            } else {
                pipe.mConNext = first;
                first.mConPrev = pipe;
            }
            mConFirst = pipe;
        } catch (Throwable e) {
            CoreUtils.closeQuietly(pipe);
            throw e;
        } finally {
            conLockRelease();
        }
    }

    /**
     * Track a new connection as being available from the tryObtainConnection method.
     * If an exception is thrown, the pipe is closed as a side effect.
     */
    void registerNewAvailableConnection(CorePipe pipe) throws RemoteException {
        conLockAcquire();
        try {
            checkClosed();
            pipe.mSession = this;
            pipe.mClock = mConClock;

            CorePipe last = mConLast;
            if (last == null) {
                mConFirst = pipe;
                mConAvail = pipe;
            } else {
                pipe.mConPrev = last;
                last.mConNext = pipe;
                if (mConAvail == null) {
                    mConAvail = pipe;
                }
            }
            mConLast = pipe;
        } catch (Throwable e) {
            CoreUtils.closeQuietly(pipe);
            throw e;
        } finally {
            conLockRelease();
        }
    }

    /**
     * Track an existing connection as being available from the tryObtainConnection method,
     * unless the pipe is M_SERVER in which case startRequestProcessor is called instead.
     *
     * @return true if the pipe is available from the tryObtainConnection method
     */
    boolean recycleConnection(CorePipe pipe) {
        recycle: {
            conLockAcquire();
            client: try {
                int mode;
                if (mClosed != 0 || (mode = pipe.mMode) == CorePipe.M_CLOSED) {
                    doRemoveConnection(pipe);
                    pipe.mMode = CorePipe.M_CLOSED;
                    break recycle;
                }

                pipe.mClock = mConClock;

                if (mode == CorePipe.M_SERVER) {
                    break client;
                }

                CorePipe avail = mConAvail;
                if (avail == pipe) {
                    // It's already available.
                    return true;
                }

                if (avail == null) {
                    mConAvail = pipe;
                }

                CorePipe next = pipe.mConNext;
                if (next == null) {
                    // It's already the last in the list.
                    assert pipe == mConLast;
                    return true;
                }

                // Remove from the list.
                CorePipe prev = pipe.mConPrev;
                if (prev == null) {
                    assert pipe == mConFirst;
                    mConFirst = next;
                } else {
                    prev.mConNext = next;
                }
                next.mConPrev = prev;

                // Add the connection as the last in the list.
                pipe.mConNext = null;
                CorePipe last = mConLast;
                pipe.mConPrev = last;
                last.mConNext = pipe;

                mConLast = pipe;

                return true;
            } finally {
                conLockRelease();
            }

            try {
                startRequestProcessor(pipe);
            } catch (IOException e) {
                if (!isClosedOrDisconnected()) {
                    uncaughtException(e);
                }
            }

            return false;
        }

        pipe.doClose();

        return false;
    }

    /**
     * Try to obtain an existing connection which is available for use. If obtained, then the
     * connection is tracked as being used and not available for other uses. The connection
     * should be recycled or closed when not used anymore.
     */
    final CorePipe tryObtainConnection() throws RemoteException {
        conLockAcquire();
        try {
            checkClosed();

            CorePipe avail = mConAvail;
            CorePipe pipe;
            if (avail == null || (pipe = mConLast) == null) {
                return null;
            }

            if (avail == pipe) {
                // Obtaining the last available connection. No need to move anything around.
                mConAvail = null;
            } else {
                CorePipe prev = pipe.mConPrev;
                mConLast = prev;

                prev.mConNext = null;
                pipe.mConPrev = null;

                // Move to the first entry in the list, to keep tracking it as unavailable.
                CorePipe first = mConFirst;
                pipe.mConNext = first;
                first.mConPrev = pipe;
                mConFirst = pipe;
            }

            return pipe;
        } finally {
            conLockRelease();
        }
    }

    final boolean hasAvailableConnection() {
        conLockAcquire();
        try {
            return mConAvail != null;
        } finally {
            conLockRelease();
        }
    }

    /**
     * Remove the connection from the tracked set and close it.
     */
    final void closeConnection(CorePipe pipe) {
        conLockAcquire();
        try {
            pipe.mMode = CorePipe.M_CLOSED;
            doRemoveConnection(pipe);
        } finally {
            conLockRelease();
        }

        pipe.doClose();
    }

    // Caller must hold mConLock.
    private void doRemoveConnection(CorePipe pipe) {
        CorePipe next = pipe.mConNext;

        if (pipe == mConAvail) {
            mConAvail = next;
        }

        CorePipe prev = pipe.mConPrev;

        if (prev != null) {
            prev.mConNext = next;
        } else if (pipe == mConFirst) {
            mConFirst = next;
        }

        if (next != null) {
            next.mConPrev = prev;
        } else if (pipe == mConLast) {
            mConLast = prev;
        }

        pipe.mConPrev = null;
        pipe.mConNext = null;
    }

    @Override
    public final SocketAddress localAddress() {
        CorePipe pipe = controlPipe();
        return pipe == null ? null : pipe.localAddress();
    }

    @Override
    public final SocketAddress remoteAddress() {
        CorePipe pipe = controlPipe();
        return pipe == null ? null : pipe.remoteAddress();
    }

    @Override
    public final void uncaughtExceptionHandler(BiConsumer<Session<?>, Throwable> h) {
        mUncaughtExceptionHandler = h;
    }

    @Override
    public void execute(Runnable command) {
        mEngine.execute(command);
    }

    @Override
    public final void close() {
        close(CLOSED, null);
    }

    /**
     * Close with a reason. When reason is DISCONNECTED and controlPipe isn't null, only closes
     * if the current control pipe matches. This guards against a race condition in which a
     * session is closed after it was reconnected. Pass null to force close.
     */
    void close(int reason, CorePipe controlPipe) {
        if ((reason & (CLOSED | DISCONNECTED)) == 0) {
            reason |= CLOSED;
        }

        CorePipe first;

        conLockAcquire();
        try {
            if ((reason & DISCONNECTED) != 0
                && controlPipe != null && mControlPipe != controlPipe)
            {
                return;
            }
            int closed = mClosed;
            if (closed == 0) {
                mClosed = reason;
            } else if ((closed & CLOSED) != 0) {
                reason |= CLOSED;
                reason &= ~DISCONNECTED;
            }
            first = mConFirst;
            mConFirst = null;
            mConAvail = null;
            mConLast = null;
        } finally {
            conLockRelease();
        }

        closePipes(first);

        mStubFactories.clear();
        mStubFactoriesByClass.clear();

        // Replace the StubSupport instance to drop any dangling thread-local pipe references
        // from unfinished batched sequences.
        var newSupport = new CoreStubSupport(this);
        stubSupport(newSupport);

        if ((reason & CLOSED) != 0) {
            mStubs.clear();
        } else {
            assert (reason & DISCONNECTED) != 0;

            mStubs.forEachToRemove(stub -> {
                if (Stub.cOriginHandle.getAcquire(stub) != null || stub == root()) {
                    // Keep the restorable stubs and tag them with the new StubSupport.
                    Stub.cSupportHandle.setRelease(stub, newSupport);
                    return false;
                }
                Stub.cSupportHandle.setRelease(stub, DisposedStubSupport.DISCONNECTED);
                return true;
            });

            R root = root();
            if (root instanceof Stub) {
                // In case the root origin has changed, replace it with the standard root
                // origin. It needs to restored specially upon reconnect anyhow.
                Stub.setRootOrigin((Stub) root);
            }
        }

        mKnownTypes.clear();

        synchronized (mSkeletons) {
            mSkeletons.forEach(this::detached);
            mSkeletons.clear();
        }

        mTypeWaitMap = null;
    }

    private static void closePipes(CorePipe pipe) {
        while (pipe != null) {
            CorePipe next = pipe.mConNext;
            pipe.mConPrev = null;
            pipe.mConNext = null;
            pipe.doClose();
            pipe = next;
        }
    }

    /**
     * Called after the session has been reconnected.
     */
    final void unclose() {
        conLockAcquire();
        mClosed = 0;
        conLockRelease();
    }

    /**
     * Move all the connections from a new unregistered session, following a reconnect. This
     * session must not have any existing connections, but they are closed just in case.
     *
     * @param from a new unregistered session
     */
    final void moveConnectionsFrom(CoreSession from) {
        CorePipe first;

        conLockAcquire();
        try {
            first = mConFirst;
            mConFirst = from.mConFirst;
            mConAvail = from.mConAvail;
            mConLast = from.mConLast;
        } finally {
            conLockRelease();
        }

        controlPipe(from.controlPipe());

        closePipes(first);
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + '@' +
            Integer.toHexString(System.identityHashCode(this)) +
            "{localAddress=" + localAddress() + ", remoteAddress=" + remoteAddress() + '}';
    }

    final void uncaughtException(Throwable e) {
        if (!CoreUtils.acceptException(mUncaughtExceptionHandler, this, e)) {
            mEngine.uncaughtException(this, e);
        }
    }

    final CorePipe controlPipe() {
        return (CorePipe) cControlPipeHandle.getAcquire(this);
    }

    final void controlPipe(CorePipe pipe) {
        conLockAcquire();
        pipe.mSession = this;
        cControlPipeHandle.setRelease(this, pipe);
        conLockRelease();
    }

    /**
     * Start a tasks to read and process commands over the control pipe, to close the session
     * if ping requests don't get responses, and to close idle available connections. A call
     * to controlPipe(CorePipe) must be made before calling startTasks.
     */
    void startTasks() throws IOException {
        CorePipe pipe = controlPipe();

        var pongTask = (Runnable) () -> {
            try {
                sendByte(C_PONG);
            } catch (Throwable e) {
                if (!(e instanceof IOException)) {
                    uncaughtException(e);
                }
                close(CONTROL_FAILURE, pipe);
            }
        };

        mEngine.executeTask(() -> {
            try {
                while (true) {
                    int command = pipe.readUnsignedByte();
                    switch (command) {
                    case C_PING:
                        mEngine.execute(pongTask);
                        break;
                    case C_PONG:
                        cPipeClockHandle.setVolatile(pipe, 0);
                        break;
                    case C_MESSAGE:
                        Object message = pipe.readObject();
                        // Ignore for now.
                        break;
                    case C_KNOWN_TYPE:
                        mKnownTypes.putIfAbsent(new Item(pipe.readLong()));
                        break;
                    case C_REQUEST_CONNECTION:
                        long id = pipe.readLong();
                        mEngine.executeTask(() -> reverseConnect(id));
                        break;
                    case C_REQUEST_INFO:
                        var typeName = (String) pipe.readObject();
                        mEngine.executeTask(() -> sendInfoResponse(pipe, typeName));
                        break;
                    case C_REQUEST_INFO_TERM:
                        mEngine.executeTask(() -> sendInfoResponseTerminator(pipe));
                        break;
                    case C_INFO_FOUND:
                        long typeId = pipe.readLong();
                        RemoteInfo info = RemoteInfo.readFrom(pipe);
                        mEngine.executeTask(() -> infoFound(pipe, typeId, info, false));
                        break;
                    case C_INFO_NOT_FOUND:
                        typeName = (String) pipe.readObject();
                        WaitMap<String, RemoteInfo> waitMap = mTypeWaitMap;
                        if (waitMap != null) {
                            waitMap.remove(typeName);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unknown command: " + command);
                    }
                }
            } catch (Throwable e) {
                if (!(e instanceof IOException)) {
                    uncaughtException(e);
                }
                close(CONTROL_FAILURE, pipe);
            }
        });

        int pingTimeoutMillis = mSettings.pingTimeoutMillis;
        if (pingTimeoutMillis >= 0) {
            long pingDelayNanos = taskDelayNanos(pingTimeoutMillis);
            mEngine.scheduleNanos(new Pinger(this, pingDelayNanos), pingDelayNanos);
        }

        int idleMillis = mSettings.idleConnectionMillis;
        if (idleMillis >= 0) {
            long idleDelayNanos = taskDelayNanos(idleMillis);
            mEngine.scheduleNanos(new Closer(this, idleDelayNanos), idleDelayNanos);
        }
    }

    private static long taskDelayNanos(long timeoutMillis) {
        // If timeoutMillis is 60_000, then the delay is 40 seconds. Effective timeout is thus
        // 40 to 80 seconds, or 60 seconds on average.
        return (long) ((timeoutMillis * 1_000_000L) / 1.5);
    }

    /**
     * @param which C_PING or C_PONG
     */
    private void sendByte(int which) throws IOException {
        mControlLock.lock();
        try {
            CorePipe pipe = controlPipe();
            pipe.write(which);
            pipe.flush();
        } finally {
            mControlLock.unlock();
        }
    }

    void sendInfoRequest(Class<?> type) throws IOException {
        mControlLock.lock();
        try {
            CorePipe pipe = controlPipe();
            pipe.write(C_REQUEST_INFO);
            pipe.writeObject(type.getName());
        } finally {
            mControlLock.unlock();
        }
    }

    void sendInfoRequestTerminator() throws IOException {
        mControlLock.lock();
        try {
            CorePipe pipe = controlPipe();
            pipe.write(C_REQUEST_INFO_TERM);
            pipe.flush();
        } finally {
            mControlLock.unlock();
        }
    }

    private void sendInfoResponse(CorePipe controlPipe, String typeName) {
        long typeId;
        Object response;

        obtain: {
            Class<?> type;
            RemoteInfo info;
            SkeletonFactory<?> factory;
            try {
                type = loadClass(typeName);
                info = RemoteInfo.examine(type);
                factory = SkeletonMaker.factoryFor(type);
            } catch (Exception e) {
                typeId = 0;
                response = typeName;
                break obtain;
            }

            typeId = factory.typeId();
            response = info;
        }

        mControlLock.lock();
        try {
            if (response instanceof RemoteInfo) {
                controlPipe.write(C_INFO_FOUND);
                controlPipe.writeLong(typeId);
                ((RemoteInfo) response).writeTo(controlPipe);
            } else {
                controlPipe.write(C_INFO_NOT_FOUND);
                controlPipe.writeObject(response);
            }
        } catch (IOException e) {
            close(CONTROL_FAILURE, controlPipe);
        } finally {
            mControlLock.unlock();
        }
    }

    private void sendInfoResponseTerminator(CorePipe controlPipe) {
        mControlLock.lock();
        try {
            // Send any object down the pipe to force the input side to disable reference mode.
            controlPipe.write(C_MESSAGE);
            controlPipe.writeObject((Object) null);
            controlPipe.flush();
        } catch (IOException e) {
            close(CONTROL_FAILURE, controlPipe);
        } finally {
            mControlLock.unlock();
        }
    }

    private void infoFound(CorePipe controlPipe, long typeId, RemoteInfo info, boolean flush) {
        WaitMap<String, RemoteInfo> waitMap = mTypeWaitMap;

        Class<?> type;
        try {
            type = loadClass(info.name());
        } catch (ClassNotFoundException e) {
            // Not expected.
            waitMap.remove(info.name());
            return;
        }

        StubFactory factory = StubMaker.factoryFor(type, typeId, info);
        factory = mStubFactories.putIfAbsent(factory);
        mStubFactoriesByClass.putIfAbsent(type, factory);

        if (waitMap != null) {
            waitMap.put(info.name(), info);
        }

        mControlLock.lock();
        try {
            controlPipe.write(C_KNOWN_TYPE);
            controlPipe.writeLong(typeId);
            if (flush) {
                controlPipe.flush();
            }
        } catch (IOException e) {
            close(CONTROL_FAILURE, controlPipe);
        } finally {
            mControlLock.unlock();
        }
    }

    void flushControlPipe() throws IOException {
        mControlLock.lock();
        try {
            controlPipe().flush();
        } finally {
            mControlLock.unlock();
        }
    }

    private static abstract class WeakScheduled extends Scheduled {
        final WeakReference<CoreSession> mSessionRef;
        final long mDelayNanos;

        WeakScheduled(CoreSession session, long delayNanos) {
            mSessionRef = new WeakReference<>(session);
            mDelayNanos = delayNanos;
        }

        @Override
        public final void run() {
            CoreSession session = mSessionRef.get();
            if (session != null && doRun(session)) {
                try {
                    session.mEngine.scheduleNanos(this, mDelayNanos);
                } catch (IOException e) {
                    session.uncaughtException(e);
                }
            }
        }

        abstract boolean doRun(CoreSession session);
    }

    private static class Pinger extends WeakScheduled {
        Pinger(CoreSession session, long delayNanos) {
            super(session, delayNanos);
        }

        @Override
        boolean doRun(CoreSession session) {
            if (session.isClosedOrDisconnected()) {
                return false;
            }

            CorePipe pipe = session.controlPipe();
            int clock = (int) cPipeClockHandle.getVolatile(pipe);

            if (clock == 1) {
                session.close(PING_FAILURE, pipe);
                return false;
            }

            cPipeClockHandle.setVolatile(pipe, 1);

            try {
                session.sendByte(C_PING);
            } catch (IOException e) {
                session.close(CONTROL_FAILURE, pipe);
                return false;
            }

            return true;
        }
    }

    private static class Closer extends WeakScheduled {
        Closer(CoreSession session, long delayNanos) {
            super(session, delayNanos);
        }

        @Override
        boolean doRun(CoreSession session) {
            return session.closeIdleConnections();
        }
    }

    /**
     * @return false if closed
     */
    private boolean closeIdleConnections() {
        conLockAcquire();

        doClose: if (mClosed == 0) {
            int clock = mConClock;

            CorePipe pipe;
            while ((pipe = mConAvail) != null && (pipe.mClock - clock) < 0) {
                doRemoveConnection(pipe);
                pipe.mMode = CorePipe.M_CLOSED;
                conLockRelease();
                pipe.doClose();
                conLockAcquire();
                if (mClosed != 0) {
                    break doClose;
                }
            }

            mConClock = clock + 1;
            conLockRelease();
            return true;
        }

        conLockRelease();
        return false;
    }

    /**
     * Returns a new or existing connection. Closing it attempts to recycle it.
     */
    abstract CorePipe connect() throws IOException;

    /**
     * Is overridden by ClientSession to reverse a connection to be used on this side for
     * making requests.
     *
     * @param id the object id to invoke
     */
    void reverseConnect(long id) {
    }

    final Object objectFor(long id) throws IOException {
        return mSkeletons.get(id).server();
    }

    final Object objectFor(long id, long typeId) throws IOException {
        StubFactory factory = mStubFactories.get(typeId);
        return mStubs.putIfAbsent(factory.newStub(id, stubSupport()));
    }

    final Object objectFor(long id, long typeId, RemoteInfo info) {
        boolean found = true;

        Class<?> type;
        try {
            type = loadClass(info.name());
        } catch (ClassNotFoundException e) {
            // The remote methods will only be available via reflection.
            type = Remote.class;
            found = false;
        }

        StubFactory factory = StubMaker.factoryFor(type, typeId, info);
        factory = mStubFactories.putIfAbsent(factory);

        // Notify the other side that it can stop sending type info.
        mEngine.tryExecuteTask(() -> notifyKnownType(typeId));

        if (found) {
            mStubFactoriesByClass.putIfAbsent(type, factory);
        }

        return mStubs.putIfAbsent(factory.newStub(id, stubSupport()));
    }

    final CoreStubSupport stubSupport() {
        return (CoreStubSupport) cStubSupportHandle.getAcquire(this);
    }

    final void stubSupport(CoreStubSupport support) {
        cStubSupportHandle.setRelease(this, support);
    }

    final StubSupport stubDispose(Stub stub) {
        mStubs.remove(stub);
        return DisposedStubSupport.EXPLICIT;
    }

    final void stubDisposed(long id, Object reason) {
        Stub removed = mStubs.remove(id);

        if (removed != null) {
            StubSupport disposed;
            if (reason instanceof Throwable) {
                disposed = new DisposedStubSupport(null, (Throwable) reason);
            } else if (reason != null) {
                disposed = new DisposedStubSupport(reason.toString(), null);
            } else {
                disposed = DisposedStubSupport.EXPLICIT;
            }
            Stub.cSupportHandle.setRelease(removed, disposed);
        }
    }

    final Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, root().getClass().getClassLoader());
    }

    private void notifyKnownType(long typeId) {
        try {
            mControlLock.lock();
            try {
                CorePipe pipe = controlPipe();
                pipe.write(C_KNOWN_TYPE);
                pipe.writeLong(typeId);
                pipe.flush();
            } finally {
                mControlLock.unlock();
            }
        } catch (IOException e) {
            // Ignore.
        }
    }

    final long remoteTypeId(Class<?> type) {
        StubFactory factory = mStubFactoriesByClass.get(type);
        return factory == null ? 0 : factory.id;
    }

    final void writeSkeleton(CorePipe pipe, Object server) throws IOException {
        writeSkeleton(pipe, mSkeletons.skeletonFor(server));
    }

    @SuppressWarnings("unchecked")
    final Skeleton createSkeletonAlias(Object server, long aliasId) {
        var type = RemoteExaminer.remoteType(server);
        SkeletonFactory factory = SkeletonMaker.factoryFor(type);
        var skeleton = factory.newSkeleton(aliasId, mSkeletonSupport, server);

        if (server instanceof SessionAware) {
            // Must call attached now, because as soon as the skeleton is put into the map, it
            // becomes available to other threads.
            try {
                ((SessionAware) server).attached(this);
            } catch (Throwable e) {
                uncaughtException(e);
            }
        }

        Skeleton existing = mSkeletons.putIfAbsent(skeleton);

        if (existing != skeleton && server instanceof SessionAware) {
            // The aliasId should be unique, and so this case shouldn't happen.
            try {
                ((SessionAware) server).detached(this);
            } catch (Throwable e) {
                uncaughtException(e);
            }
        }

        return existing;
    }

    final void writeSkeletonAlias(CorePipe pipe, Object server, long aliasId) throws IOException {
        Skeleton skeleton = createSkeletonAlias(server, aliasId);
        try {
            writeSkeleton(pipe, skeleton);
        } catch (Throwable e) {
            removeSkeleton(skeleton);
            throw e;
        }
    }

    private void writeSkeleton(CorePipe pipe, Skeleton skeleton) throws IOException {
        if (mKnownTypes.tryGet(skeleton.typeId()) != null) {
            // Write the remote identifier and the remote type.
            pipe.writeSkeletonHeader((byte) TypeCodes.T_REMOTE_T, skeleton);
        } else {
            // Write the remote identifier, the remote type, and the remote info.
            RemoteInfo info = RemoteInfo.examine(skeleton.type());
            pipe.writeSkeletonHeader((byte) TypeCodes.T_REMOTE_TI, skeleton);
            info.writeTo(pipe);
        }
    }

    final void removeSkeleton(Skeleton<?> skeleton) {
        if (mSkeletons.remove(skeleton) != null) {
            detached(skeleton);
        }
    }

    private void detached(Skeleton<?> skeleton) {
        Object server = skeleton.server();
        if (server instanceof SessionAware) {
            try {
                ((SessionAware) server).detached(this);
            } catch (Throwable e) {
                uncaughtException(e);
            }
        }
    }

    final boolean isClosed() {
        conLockAcquire();
        int closed = mClosed;
        conLockRelease();
        return (closed & CLOSED) != 0;
    }

    final boolean isClosedOrDisconnected() {
        conLockAcquire();
        int closed = mClosed;
        conLockRelease();
        return closed != 0;
    }

    final void checkClosed() throws RemoteException {
        int closed = mClosed;

        if (closed != 0) {
            StringBuilder b = new StringBuilder(80).append("Session is ");

            b.append((closed & DISCONNECTED) == 0 ? "closed" : "disconnected");

            if ((closed & PING_FAILURE) != 0) {
                b.append(" (ping response timeout)");
            } else if ((closed & CONTROL_FAILURE) != 0) {
                b.append(" (control connection failure)");
            }

            if ((closed & DISCONNECTED) != 0) {
                b.append("; attempting to reconnect");
            }

            String message = b.toString();

            if ((closed & DISCONNECTED) != 0) {
                throw new DisconnectedException(message);
            } else {
                throw new ClosedException(message);
            }
        }
    }

    final void conLockAcquire() {
        int trials = 0;
        while (mConLock != 0 || !cConLockHandle.compareAndSet(this, 0, 1)) {
            if (++trials >= SPIN_LIMIT) {
                Thread.yield();
                trials = 0;
            } else {
                Thread.onSpinWait();
            }
        }
    }

    final void conLockRelease() {
        mConLock = 0;
    }

    /**
     * Start processing incoming remote requests over the given pipe. If an exception is
     * thrown, the pipe is closed.
     */
    final void startRequestProcessor(CorePipe pipe) throws IOException {
        try {
            mEngine.executeTask(new Processor(pipe));
        } catch (IOException e) {
            closeConnection(pipe);
            throw e;
        }
    }

    private final class Processor implements Runnable, Closeable {
        private final CorePipe mPipe;

        Processor(CorePipe pipe) {
            mPipe = pipe;
        }

        @Override
        public void run() {
            try {
                CoreUtils.cCurrentSession.set(CoreSession.this);
                Object context = null;
                do {
                    Skeleton skeleton = mSkeletons.get(mPipe.readLong());
                    context = skeleton.invoke(mPipe, context);
                    skeleton = null;
                } while (context != Skeleton.STOP_READING);
            } catch (Throwable e) {
                if (e instanceof UncaughtException) {
                    uncaughtException(e.getCause());
                } else if (e instanceof NoSuchObjectException || !(e instanceof IOException)) {
                    uncaughtException(e);
                }
                CoreUtils.closeQuietly(this);
            } finally {
                CoreUtils.cCurrentSession.remove();
            }
        }

        @Override
        public void close() throws IOException {
            mPipe.close();
        }
    }
}
