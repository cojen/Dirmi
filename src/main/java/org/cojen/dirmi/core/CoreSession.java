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

import java.io.IOException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;

/**
 * Base class for ClientSession and ServerSession.
 *
 * @author Brian S O'Neill
 */
abstract class CoreSession<R> extends Item implements Session<R> {
    // Control commands.
    private static final int C_KNOWN_TYPE = 2;

    private static final int SPIN_LIMIT;

    private static final VarHandle cConLockHandle;

    static {
        SPIN_LIMIT = Runtime.getRuntime().availableProcessors() > 1 ? 1 << 10 : 1;

        try {
            var lookup = MethodHandles.lookup();
            cConLockHandle = lookup.findVarHandle(CoreSession.class, "mConLock", int.class);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    final Engine mEngine;
    final ItemMap<Stub> mStubs;
    final ItemMap<StubFactory> mStubFactories;
    final SkeletonMap mSkeletons;
    final ItemMap<Item> mKnownTypes; // tracks types known by the client-side

    final CoreStubSupport mStubSupport;
    final CoreSkeletonSupport mSkeletonSupport;

    private final Lock mControlLock;
    private CorePipe mControlPipe;

    private volatile int mConLock;

    // Linked list of connections. Connections which range from first to before avail are
    // currently being used. Connections which range from avail to last are waiting to be used.
    private CorePipe mConFirst, mConAvail, mConLast;

    private boolean mClosed;

    /**
     * @param idType type of skeleton ids to generate; see IdGenerator
     */
    CoreSession(Engine engine, long idType) {
        super(IdGenerator.next());
        mEngine = engine;
        mStubs = new ItemMap<Stub>();
        mStubFactories = new ItemMap<StubFactory>();
        mSkeletons = new SkeletonMap(this, idType);
        mKnownTypes = new ItemMap<>();

        mStubSupport = new CoreStubSupport(this);
        mSkeletonSupport = new CoreSkeletonSupport(this);

        mControlLock = new ReentrantLock();
    }

    /**
     * Track a new connection as being immediately used (not available for other uses).
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
        } finally {
            conLockRelease();
        }
    }

    /**
     * Track a new connection as being available from the tryObtainConnection method.
     */
    final void registerNewAvailableConnection(CorePipe pipe) throws ClosedException {
        conLockAcquire();
        recycle: try {
            checkClosed();
            pipe.mSession = this;

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
        } finally {
            conLockRelease();
        }
    }

    /**
     * Track an existing connection as being available from the tryObtainConnection method.
     */
    boolean recycleConnection(CorePipe pipe) {
        conLockAcquire();
        recycle: try {
            if (mClosed || pipe.mClosed) {
                doRemoveConnection(pipe);
                pipe.mClosed = true;
                break recycle;
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

    /**
     * Remove the connection from the tracked set and close it.
     */
    final void closeConnection(CorePipe pipe) {
        conLockAcquire();
        try {
            pipe.mClosed = true;
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
    public void close() {
        CorePipe pipe;

        conLockAcquire();
        try {
            mClosed = true;
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
        mSkeletons.clear();
        mKnownTypes.clear();
    }

    /**
     * Starts a task to read and process commands over the control connection.
     */
    final void processControlConnection(CorePipe pipe) throws IOException {
        mControlLock.lock();
        mControlPipe = pipe;
        mControlLock.unlock();

        mEngine.execute(() -> {
            try {
                while (true) {
                    int command = pipe.readUnsignedByte();
                    switch (command) {
                    case C_KNOWN_TYPE:
                        mKnownTypes.putIfAbsent(new Item(pipe.readLong()));
                        break;
                    default:
                        throw new RemoteException("Unknown command: " + command);
                    }
                }
            } catch (Throwable e) {
                // FIXME: pass the exception so that it can be logged
                close();
            }
        });
    }

    /**
     * Returns a new or existing connection. Closing it attempts to recycle it.
     */
    abstract CorePipe connect() throws IOException;

    Stub stubFor(long id) throws IOException {
        return mStubs.get(id);
    }

    Stub stubFor(long id, long typeId) throws IOException {
        StubFactory factory = mStubFactories.get(typeId);
        return mStubs.putIfAbsent(factory.newStub(id, mStubSupport));
    }

    Stub stubFor(long id, long typeId, RemoteInfo info) throws IOException {
        Class<?> type;
        try {
            type = Class.forName(info.name(), false, root().getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            // The remote methods will only be available via reflection.
            type = Remote.class;
        }

        StubFactory factory = StubMaker.factoryFor(type, typeId, info);
        factory = mStubFactories.putIfAbsent(factory);

        // Notify the other side that it can stop sending type info.
        mEngine.tryExecute(() -> notifyKnownType(typeId));

        return mStubs.putIfAbsent(factory.newStub(id, mStubSupport));
    }

    private void notifyKnownType(long typeId) {
        try {
            mControlLock.lock();
            try {
                mControlPipe.write(C_KNOWN_TYPE);
                mControlPipe.writeLong(typeId);
                mControlPipe.flush();
            } finally {
                mControlLock.unlock();
            }
        } catch (IOException e) {
            // Ignore.
        }
    }

    void writeSkeleton(CorePipe pipe, Object server) throws IOException {
        Skeleton<?> skeleton = mSkeletons.skeletonFor(server);

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

    private void checkClosed() throws ClosedException {
        if (mClosed) {
            throw new ClosedException("Session is closed");
        }
    }

    private void conLockAcquire() {
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

    private void conLockRelease() {
        mConLock = 0;
    }
}
