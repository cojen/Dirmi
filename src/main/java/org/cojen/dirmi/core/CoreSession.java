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
import java.io.ObjectStreamException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.lang.ref.WeakReference;

import java.net.SocketAddress;

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import java.util.function.BiConsumer;

import org.cojen.dirmi.ClassResolver;
import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.DisconnectedException;
import org.cojen.dirmi.NoSuchObjectException;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.SessionAware;

import org.cojen.dirmi.io.CaptureOutputStream;

/**
 * Base class for ClientSession and ServerSession.
 *
 * @author Brian S O'Neill
 */
abstract class CoreSession<R> extends Item implements Session<R> {
    // Control commands.
    static final int
        C_PING = 1, C_PONG = 2, C_MESSAGE = 3, C_KNOWN_TYPE = 4, C_REQUEST_CONNECTION = 5,
        C_REQUEST_INFO = 6, C_REQUEST_INFO_TERM = 7, C_INFO_FOUND = 8, C_INFO_NOT_FOUND = 9,
        C_SKELETON_DISPOSE = 10, C_STUB_DISPOSE = 11;

    static final int R_CLOSED = 1, R_DISCONNECTED = 2, R_PING_FAILURE = 4, R_CONTROL_FAILURE = 8;

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

    final Lock mStateLock;
    private volatile BiConsumer<Session<?>, Throwable> mStateListener;
    volatile State mState;

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

        mStateLock = new ReentrantLock();

        initTypeCodeMap(TypeCodeMap.STANDARD);
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
     *
     * @param sessionId if non-zero, must match the current local session id
     */
    void registerNewAvailableConnection(CorePipe pipe, long sessionId) throws RemoteException {
        conLockAcquire();
        try {
            checkClosed();

            if (sessionId != 0 && sessionId != id) {
                throw new RemoteException("Connection belongs to an old session");
            }

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
    public final Class<?> resolveClass(String name) throws IOException, ClassNotFoundException {
        ClassResolver resolver = mSettings.resolver;
        try {
            if (resolver != null) {
                Class<?> clazz = resolver.resolveClass(name);
                if (clazz != null) {
                    return clazz;
                }
            }
            return loadClass(name);
        } catch (IOException | ClassNotFoundException e) {
            throw e;
        } catch (Throwable e) {
            throw new ClassNotFoundException(name, e);
        }
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
    public final State state() {
        return mState;
    }

    @Override
    public final void stateListener(BiConsumer<Session<?>, Throwable> listener) {
        if (listener == null) {
            mStateListener = null;
        } else {
            mStateLock.lock();
            try {
                mStateListener = listener;
                try {
                    listener.accept(this, null);
                } catch (Throwable e) {
                    uncaughtException(e);
                }
            } finally {
                mStateLock.unlock();
            }
        }
    }

    // Caller must hold mStateLock.
    private void setStateAndNotify(State state) {
        if (mState != state) {
            mState = state;

            BiConsumer<Session<?>, Throwable> listener = mStateListener;

            if (listener != null) {
                try {
                    listener.accept(this, null);
                } catch (Throwable e) {
                    uncaughtException(e);
                }
            }
        }
    }

    void casStateAndNotify(State expect, State newState) {
        mStateLock.lock();
        try {
            if (mState == expect) {
                setStateAndNotify(newState);
            }
        } finally {
            mStateLock.unlock();
        }
    }

    void reconnectFailureNotify(Throwable ex) {
        BiConsumer<Session<?>, Throwable> listener = mStateListener;

        if (listener != null) {
            mStateLock.lock();
            try {
                listener = mStateListener;

                if (listener != null) {
                    try {
                        listener.accept(this, ex);
                    } catch (Throwable e) {
                        uncaughtException(e);
                    }
                }
            } finally {
                mStateLock.unlock();
            }
        }
    }

    @Override
    public final void close() {
        close(R_CLOSED, null);
    }

    /**
     * Close with a reason. When reason is R_DISCONNECTED and controlPipe isn't null, only
     * closes if the current control pipe matches. This guards against a race condition in
     * which a session is closed after it was reconnected. Pass null to force close.
     *
     * @return true if just moved to a closed state
     */
    boolean close(int reason, CorePipe controlPipe) {
        if ((reason & (R_CLOSED | R_DISCONNECTED)) == 0) {
            reason |= R_CLOSED;
        }

        CorePipe first;
        boolean justClosed = false;

        mStateLock.lock();
        try {
            if ((reason & R_DISCONNECTED) != 0
                && controlPipe != null && mControlPipe != controlPipe)
            {
                return false;
            }

            int closed = mClosed;
            if (closed == 0) {
                mClosed = reason;
                justClosed = true;
            } else if ((closed & R_CLOSED) != 0) {
                reason |= R_CLOSED;
                reason &= ~R_DISCONNECTED;
            }

            setStateAndNotify((reason & R_DISCONNECTED) != 0 ? State.DISCONNECTED : State.CLOSED);

            conLockAcquire();
            try {
                first = mConFirst;
                mConFirst = null;
                mConAvail = null;
                mConLast = null;
                markPipesClosed(first);
            } finally {
                conLockRelease();
            }
        } finally {
            mStateLock.unlock();
        }

        closePipes(first);

        mStubFactories.clear();
        mStubFactoriesByClass.clear();

        // Replace the StubSupport instance to drop any dangling thread-local pipe references
        // from unfinished batched sequences.
        var newSupport = new CoreStubSupport(this);
        stubSupport(newSupport);

        if ((reason & R_CLOSED) != 0) {
            mStubs.clear();
        } else {
            assert (reason & R_DISCONNECTED) != 0;

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

        return justClosed;
    }

    // Caller must hold mConLock.
    private static void markPipesClosed(CorePipe pipe) {
        for (; pipe != null; pipe = pipe.mConNext) {
            pipe.mMode = CorePipe.M_CLOSED;
        }
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
        mStateLock.lock();
        try {
            if (mState == State.DISCONNECTED) {
                // Reconnected too quickly, before the RECONNECTING state was set. The
                // RECONNECTING state should always be observed by the listener before
                // observing RECONNECTED, so set it now.
                setStateAndNotify(State.RECONNECTING);
            }

            setStateAndNotify(State.RECONNECTED);

            conLockAcquire();
            mClosed = 0;
            conLockRelease();

            setStateAndNotify(State.CONNECTED);
        } finally {
            mStateLock.unlock();
        }
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
            markPipesClosed(first);
        } finally {
            conLockRelease();
        }

        controlPipe(from.controlPipe());

        closePipes(first);
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + '@' +
            Integer.toHexString(System.identityHashCode(this)) + "{state=" + state() +
            ", localAddress=" + localAddress() + ", remoteAddress=" + remoteAddress() + '}';
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
        mStateLock.lock();
        pipe.mSession = this;
        cControlPipeHandle.setRelease(this, pipe);
        mStateLock.unlock();
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
                close(R_CONTROL_FAILURE, pipe);
            }
        };

        mEngine.executeTask(() -> {
            try {
                while (true) {
                    int command = pipe.readUnsignedByte();
                    switch (command) {
                    case C_PING:
                        mEngine.executeTask(pongTask);
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
                    case C_SKELETON_DISPOSE:
                        Skeleton<?> skeleton = mSkeletons.remove(pipe.readLong());
                        if (skeleton != null) {
                            // Pass newTask=true to prevent blocking the control thread.
                            detached(skeleton, true);
                        }
                        break;
                    case C_STUB_DISPOSE:
                        id = pipe.readLong();
                        stubDispose(id, "Object is disposed by remote endpoint");
                        // Send the reply command. See the serverDispose method.
                        mEngine.executeTask(() -> trySendCommandAndId(C_SKELETON_DISPOSE, id));
                        break;
                    default:
                        throw new IllegalStateException("Unknown command: " + command);
                    }
                }
            } catch (Throwable e) {
                if (!(e instanceof IOException)) {
                    uncaughtException(e);
                }
                close(R_CONTROL_FAILURE, pipe);
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
            close(R_CONTROL_FAILURE, controlPipe);
        } finally {
            mControlLock.unlock();
        }
    }

    private void sendInfoResponseTerminator(CorePipe controlPipe) {
        mControlLock.lock();
        try {
            // Send any object down the pipe to force the input side to disable reference mode.
            controlPipe.write(C_MESSAGE);
            controlPipe.writeNull();
            controlPipe.flush();
        } catch (IOException e) {
            close(R_CONTROL_FAILURE, controlPipe);
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
            close(R_CONTROL_FAILURE, controlPipe);
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
                session.close(R_PING_FAILURE, pipe);
                return false;
            }

            cPipeClockHandle.setVolatile(pipe, 1);

            try {
                session.sendByte(C_PING);
            } catch (IOException e) {
                session.close(R_CONTROL_FAILURE, pipe);
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
        mEngine.tryExecuteTask(() -> trySendCommandAndId(C_KNOWN_TYPE, typeId));

        if (found) {
            mStubFactoriesByClass.putIfAbsent(type, factory);
        }

        return mStubs.putIfAbsent(factory.newStub(id, stubSupport()));
    }

    private void trySendCommandAndId(int command, long id) {
        mControlLock.lock();
        try {
            CorePipe pipe = controlPipe();
            pipe.write(command);
            pipe.writeLong(id);
            pipe.flush();
        } catch (IOException e) {
            // Ignore.
        } finally {
            mControlLock.unlock();
        }
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

    final boolean stubDispose(long id, String message) {
        Stub removed = mStubs.remove(id);

        if (removed == null) {
            return false;
        }

        StubSupport disposed;
        if (message == null) {
            disposed = DisposedStubSupport.EXPLICIT;
        } else {
            disposed = new DisposedStubSupport(message);
        }

        Stub.cSupportHandle.setRelease(removed, disposed);

        return true;
    }

    final boolean stubDisposeAndNotify(Stub stub, String message) {
        long id = stub.id;
        if (stubDispose(id, message)) {
            // Notify the remote side to dispose the associated skeleton. If the command cannot
            // be sent, the skeleton will be disposed anyhow due to disconnect.
            trySendCommandAndId(C_SKELETON_DISPOSE, id);
            return true;
        } else {
            return false;
        }
    }

    final boolean serverDispose(Object server) {
        Skeleton skeleton;
        if (server == null || (skeleton = mSkeletons.skeletonFor(server, false)) == null) {
            return false;
        }

        // Notify the remote side to dispose the associated stub, and then it will send a reply
        // command back to this side to dispose the skeleton. This helps prevent race
        // conditions in which the remote side attempts to use a disposed object before it
        // knows that it was disposed. When this happens, an uncaught NoSuchObjectException is
        // generated on this side, and the remote side just gets a plain ClosedException.

        // If the original command or reply cannot be sent, the stub and skeleton will be
        // disposed anyhow due to disconnect.

        trySendCommandAndId(C_STUB_DISPOSE, skeleton.id);

        return true;
    }

    final Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, root().getClass().getClassLoader());
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

        if (server instanceof SessionAware sa) {
            // Must notify of attachment now, because as soon as the skeleton is put into the
            // map, it becomes available to other threads.
            attachNotify(sa);
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

    final Skeleton<?> createBrokenSkeletonAlias(Class<?> type, long aliasId, Throwable exception) {
        SkeletonFactory<?> factory = SkeletonMaker.factoryFor(type);
        Skeleton<?> skeleton = factory.newSkeleton(exception, aliasId, mSkeletonSupport);
        return mSkeletons.putIfAbsent(skeleton);
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

    final void writeBrokenSkeletonAlias(CorePipe pipe,
                                        Class<?> type, long aliasId, Throwable exception)
        throws IOException
    {
        Skeleton skeleton = createBrokenSkeletonAlias(type, aliasId, exception);
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

    static MarshalledSkeleton marshallSkeleton(CorePipe pipe, Object server) {
        return pipe.mSession.marshallSkeleton(server);
    }

    private MarshalledSkeleton marshallSkeleton(Object server) {
        Skeleton skeleton = mSkeletons.skeletonFor(server);

        long typeId = skeleton.typeId();
        byte[] infoBytes;

        if (mKnownTypes.tryGet(typeId) != null) {
            infoBytes = null;
        } else {
            RemoteInfo info = RemoteInfo.examine(skeleton.type());
            var out = new CaptureOutputStream();
            var pipe = new BufferedPipe(InputStream.nullInputStream(), out);
            try {
                info.writeTo(pipe);
                pipe.flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            infoBytes = out.getBytes();
        }

        return new MarshalledSkeleton(skeleton.id, typeId, infoBytes);
    }

    final void removeSkeleton(Skeleton<?> skeleton) {
        if (mSkeletons.remove(skeleton) != null) {
            detached(skeleton);
        }
    }

    private void detached(Skeleton<?> skeleton) {
        detached(skeleton, false);
    }

    private void detached(Skeleton<?> skeleton, boolean newTask) {
        Object server = skeleton.server();

        if (server instanceof SessionAware sa) {
            if (newTask) {
                Runnable task = () -> {
                    try {
                        sa.detached(this);
                    } catch (Throwable e) {
                        uncaughtException(e);
                    }
                };

                if (mEngine.tryExecuteTask(task)) {
                    return;
                }
            }

            try {
                sa.detached(this);
            } catch (Throwable e) {
                uncaughtException(e);
            }
        }
    }

    final void attachNotify(SessionAware sa) {
        // Special handling is required for the ServerSession root, which is why the state is
        // checked first. The skeleton is needed early, and it's assigned by the ServerSession
        // constructor. The SkeletonMap then calls into this method, but the initial state is
        // null to prevent a race condition with initTypeCodeMap. Only after the ServerSession
        // is fully connected can a SessionAware root be notified of attachment. See
        // ServerSession.accepted and Engine.accepted.
        if (mState != null) {
            try {
                sa.attached(this);
            } catch (Throwable e) {
                uncaughtException(e);
            }
        }
    }

    final boolean isClosed() {
        conLockAcquire();
        int closed = mClosed;
        conLockRelease();
        return (closed & R_CLOSED) != 0;
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
            throwClosed(closed);
        }
    }

    private void throwClosed(int closed) throws RemoteException {
        StringBuilder b = new StringBuilder(80).append("Session is ");

        b.append((closed & R_DISCONNECTED) == 0 ? "closed" : "disconnected");

        if ((closed & R_PING_FAILURE) != 0) {
            b.append(" (ping response timeout)");
        } else if ((closed & R_CONTROL_FAILURE) != 0) {
            b.append(" (control connection failure)");
        }

        if ((closed & R_DISCONNECTED) != 0) {
            b.append("; attempting to reconnect");
        }

        String message = b.toString();

        if ((closed & R_DISCONNECTED) != 0) {
            throw new DisconnectedException(message);
        } else {
            throw new ClosedException(message);
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

    private final class Processor implements Runnable {
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
                } else if (!isClosedOrDisconnected()) {
                    if (e instanceof NoSuchObjectException ||
                        e instanceof ObjectStreamException ||
                        e instanceof ClassNotFoundException ||
                        !(e instanceof IOException))
                    {
                        uncaughtException(e);
                    }
                }
                CoreUtils.closeQuietly(mPipe);
            } finally {
                CoreUtils.cCurrentSession.remove();
            }
        }
    }
}
