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

import java.util.WeakHashMap; // FIXME: use something custom

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import java.util.function.BiConsumer;

import org.cojen.dirmi.ClosedException;
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
    static final int C_PING = 1, C_PONG = 2,
        C_KNOWN_TYPE = 3, C_REQUEST_CONNECTION = 4, C_MESSAGE = 5;

    private static final int CLOSED = 1, CLOSED_PING_FAILURE = 2, CLOSED_CONTROL_FAILURE = 3;

    private static final int SPIN_LIMIT;

    private static final VarHandle cControlPipeHandle, cConLockHandle, cPipeClockHandle;

    static {
        SPIN_LIMIT = Runtime.getRuntime().availableProcessors() > 1 ? 1 << 10 : 1;

        try {
            var lookup = MethodHandles.lookup();
            cControlPipeHandle = lookup.findVarHandle
                (CoreSession.class, "mControlPipe", CorePipe.class);
            cConLockHandle = lookup.findVarHandle(CoreSession.class, "mConLock", int.class);
            cPipeClockHandle = lookup.findVarHandle(CorePipe.class, "mClock", int.class);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    final Engine mEngine;
    final ItemMap<Stub> mStubs;
    final ItemMap<StubFactory> mStubFactories;
    final SkeletonMap mSkeletons;
    final ItemMap<Item> mKnownTypes; // tracks types known by the remote client

    final CoreStubSupport mStubSupport;
    final CoreSkeletonSupport mSkeletonSupport;

    final Lock mControlLock;
    CorePipe mControlPipe;

    private volatile int mConLock;

    // Linked list of connections. Connections which range from first to before avail are
    // currently being used. Connections which range from avail to last are waiting to be used.
    private CorePipe mConFirst, mConAvail, mConLast;

    private int mConClock;

    final WeakHashMap<Class<?>, StubFactory> mStubFactoriesByClass;

    private volatile BiConsumer<Session, Throwable> mUncaughtExceptionHandler;

    private int mClosed;

    CoreSession(Engine engine) {
        super(IdGenerator.next());
        mEngine = engine;
        mStubs = new ItemMap<Stub>();
        mStubFactories = new ItemMap<StubFactory>();
        mSkeletons = new SkeletonMap(this);
        mKnownTypes = new ItemMap<>();

        mStubSupport = new CoreStubSupport(this);
        mSkeletonSupport = new CoreSkeletonSupport(this);

        mControlLock = new ReentrantLock();

        mStubFactoriesByClass = new WeakHashMap<>(4);
    }

    /**
     * Track a new connection as being immediately used (not available for other uses).
     * If an exception is thrown, the pipe is closed as a side effect.
     */
    final void registerNewConnection(CorePipe pipe) throws ClosedException {
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
    void registerNewAvailableConnection(CorePipe pipe) throws ClosedException {
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
                if (!isClosed()) {
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
    final CorePipe tryObtainConnection() throws ClosedException {
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
     * Remove the connection from the tracked set without closing it.
     */
    final void removeConnection(CorePipe pipe) {
        conLockAcquire();
        try {
            doRemoveConnection(pipe);
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
        var pipe = (CorePipe) cControlPipeHandle.getAcquire(this);
        return pipe == null ? null : pipe.localAddress();
    }

    @Override
    public final SocketAddress remoteAddress() {
        var pipe = (CorePipe) cControlPipeHandle.getAcquire(this);
        return pipe == null ? null : pipe.remoteAddress();
    }

    @Override
    public final void uncaughtExceptionHandler(BiConsumer<Session, Throwable> h) {
        mUncaughtExceptionHandler = h;
    }

    @Override
    public final void close() {
        close(CLOSED);
    }

    void close(int reason) {
        CorePipe pipe;

        conLockAcquire();
        try {
            if (mClosed == 0) {
                mClosed = reason;
            }
            pipe = mConFirst;
            mConFirst = null;
            mConAvail = null;
            mConLast = null;
        } finally {
            conLockRelease();
        }

        while (pipe != null) {
            CorePipe next = pipe.mConNext;
            pipe.mConPrev = null;
            pipe.mConNext = null;
            pipe.doClose();
            pipe = next;
        }

        mStubs.clear();
        mStubFactories.clear();
        mKnownTypes.clear();

        synchronized (mStubFactoriesByClass) {
            mStubFactoriesByClass.clear();
        }

        synchronized (mSkeletons) {
            mSkeletons.forEach(this::detached);
            mSkeletons.clear();
        }
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

    final void setControlConnection(CorePipe pipe) {
        cControlPipeHandle.setRelease(this, pipe);
    }

    /**
     * Start a tasks to read and process commands over the control connection, to close the
     * session if ping requests don't get responses, and to close idle available connections.
     *
     * @param pipe control pipe
     * @param ageMillis average age of idle connection before being closed
     */
    final void startTasks(CorePipe pipe, long pingTimeoutMillis, long ageMillis)
        throws IOException
    {
        setControlConnection(pipe);

        var pongTask = (Runnable) () -> {
            try {
                sendByte(C_PONG);
            } catch (Throwable e) {
                if (!(e instanceof IOException)) {
                    uncaughtException(e);
                }
                close(CLOSED_CONTROL_FAILURE);
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
                    case C_KNOWN_TYPE:
                        mKnownTypes.putIfAbsent(new Item(pipe.readLong()));
                        break;
                    case C_REQUEST_CONNECTION:
                        long id = pipe.readLong();
                        mEngine.executeTask(() -> reverseConnect(id));
                        break;
                    case C_MESSAGE:
                        Object message = pipe.readObject();
                        // Ignore for now.
                        break;
                    default:
                        throw new IllegalStateException("Unknown command: " + command);
                    }
                }
            } catch (Throwable e) {
                if (!(e instanceof IOException)) {
                    uncaughtException(e);
                }
                close(CLOSED_CONTROL_FAILURE);
            }
        });

        long pingDelayNanos = taskDelayNanos(pingTimeoutMillis);
        mEngine.scheduleNanos(new Pinger(this, pingDelayNanos), pingDelayNanos);

        long ageDelayNanos = taskDelayNanos(ageMillis);
        mEngine.scheduleNanos(new Closer(this, ageDelayNanos), ageDelayNanos);
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
            CorePipe pipe = mControlPipe;
            pipe.write(which);
            pipe.flush();
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
            if (session.isClosed()) {
                return false;
            }

            var pipe = (CorePipe) cControlPipeHandle.getAcquire(session);
            int clock = (int) cPipeClockHandle.getVolatile(pipe);

            if (clock == 1) {
                session.close(CLOSED_PING_FAILURE);
                return false;
            }

            cPipeClockHandle.setVolatile(pipe, 1);

            try{
                session.sendByte(C_PING);
            } catch (IOException e) {
                session.close(CLOSED_CONTROL_FAILURE);
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
        return mStubs.putIfAbsent(factory.newStub(id, mStubSupport));
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
            synchronized (mStubFactoriesByClass) {
                mStubFactoriesByClass.putIfAbsent(type, factory);
            }
        }

        return mStubs.putIfAbsent(factory.newStub(id, mStubSupport));
    }

    final StubSupport stubDispose(Stub stub) {
        mStubs.remove(stub);
        return DisposedStubSupport.THE;
    }

    final void stubDisposed(long id, Object reason) {
        Stub removed = mStubs.remove(id);

        if (removed != null) {
            StubSupport disposed;
            if (reason instanceof Throwable) {
                disposed = new DisposedStubSupport((Throwable) reason);
            } else {
                disposed = DisposedStubSupport.THE;
            }
            Stub.SUPPORT_HANDLE.setOpaque(removed, disposed);
        }
    }

    final Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, root().getClass().getClassLoader());
    }

    private void notifyKnownType(long typeId) {
        try {
            mControlLock.lock();
            try {
                CorePipe pipe = mControlPipe;
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
        StubFactory factory;
        synchronized (mStubFactoriesByClass) {
            factory = mStubFactoriesByClass.get(type);
        }
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
        return closed != 0;
    }

    final void checkClosed() throws ClosedException {
        int closed = mClosed;
        if (closed != 0) {
            String message = "Session is closed";

            if (closed == CLOSED_PING_FAILURE) {
                message += " (ping response timeout)";
            } else if (closed == CLOSED_CONTROL_FAILURE) {
                message += " (control connection failure)";
            }

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

    private final class Processor implements Runnable, Closeable {
        private final CorePipe mPipe;

        Processor(CorePipe pipe) {
            mPipe = pipe;
        }

        @Override
        public void run() {
            try {
                CoreUtils.CURRENT_SESSION.set(CoreSession.this);
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
                CoreUtils.CURRENT_SESSION.remove();
            }
        }

        @Override
        public void close() throws IOException {
            mPipe.close();
        }
    }
}
