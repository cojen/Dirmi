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

import org.cojen.dirmi.Session;

/**
 * Base class for ClientSession and ServerSession.
 *
 * @author Brian S O'Neill
 */
abstract class CoreSession<R> extends Item implements Session<R> {
    private static final int SPIN_LIMIT;

    private static final VarHandle cConLockHandle, cConVersionHandle, cPipeVersionHandle;

    static {
        SPIN_LIMIT = Runtime.getRuntime().availableProcessors() > 1 ? 1 << 10 : 1;

        try {
            var lookup = MethodHandles.lookup();
            cConLockHandle = lookup.findVarHandle(CoreSession.class, "mConLock", int.class);
            cConVersionHandle = lookup.findVarHandle(CoreSession.class, "mConVersion", int.class);
            cPipeVersionHandle = lookup.findVarHandle(CorePipe.class, "mVersion", int.class);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    final Engine mEngine;

    private volatile int mConLock;

    // Linked list of connections. Connections which range from first to before avail are
    // currently being used. Connections which range from avail to last are waiting to be used.
    private CorePipe mConFirst, mConAvail, mConLast;

    // Valid versions are 1 to max int.
    private int mConVersion;

    CoreSession(Engine engine) {
        super(IdGenerator.next());
        mEngine = engine;
        mConVersion = 1;
        VarHandle.storeStoreFence();
    }

    /**
     * Track a new connection as being immediately used (not available for other uses).
     */
    protected final void registerNewConnection(CorePipe pipe) {
        conLockAcquire();
        try {
            pipe.mVersion = mConVersion;
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
     * Track a new or existing connection as being available for use.
     */
    protected final void recycleConnection(CorePipe pipe) {
        conLockAcquire();
        recycle: try {
            int pipeVersion = pipe.mVersion;

            if (pipeVersion < 0) {
                // Was closed or removed.
                return;
            }

            int conVersion = mConVersion;

            if (pipeVersion == conVersion) {
                // Existing connection.

                CorePipe avail = mConAvail;
                if (avail == pipe) {
                    // It's already available.
                    return;
                }

                if (avail == null) {
                    mConAvail = pipe;
                }

                CorePipe next = pipe.mConNext;
                if (next == null) {
                    // It's already the last in the list.
                    assert pipe == mConLast;
                    return;
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
            } else if (pipeVersion == 0) {
                // New connection.
                pipe.mVersion = conVersion;
                CorePipe last = mConLast;
                if (last == null) {
                    mConFirst = pipe;
                    mConAvail = pipe;
                } else {
                    pipe.mConPrev = last;
                    last.mConNext = pipe;
                    if (mConAvail == null) {
                        mConAvail = last;
                    }
                }
            } else {
                // Old connection; close it.
                break recycle;
            }
                
            mConLast = pipe;

            return;
        } finally {
            conLockRelease();
        }

        doClose(pipe);
    }

    /**
     * Try to obtain an existing connection which is available for use. If obtained, then the
     * connection is tracked as being used and not available for other uses. The connection
     * should be recycled or closed when not used anymore.
     */
    protected final CorePipe tryObtainConnection() {
        conLockAcquire();
        try {
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
    protected final void closeConnection(CorePipe pipe) {
        conLockAcquire();
        try {
            if (pipe.mVersion == mConVersion) {
                pipe.mVersion = -1;

                CorePipe next = pipe.mConNext;

                if (pipe == mConAvail) {
                    mConAvail = next;
                }

                CorePipe prev = pipe.mConPrev;

                if (prev == null) {
                    assert pipe == mConFirst;
                    mConFirst = next;
                } else {
                    prev.mConNext = next;
                }

                if (next == null) {
                    assert pipe == mConLast;
                    mConLast = prev;
                } else {
                    next.mConPrev = prev;
                }
            }
        } finally {
            conLockRelease();
        }

        doClose(pipe);
    }

    /**
     * Removes all connections from the tracked set and closes them.
     */
    protected final void closeAllConnections() {
        CorePipe pipe;

        conLockAcquire();
        try {
            int version = mConVersion + 1;
            if (version <= 0) { // wrapped around
                version = 1;
            }
            mConVersion = version;

            pipe = mConFirst;
            mConFirst = null;
            mConAvail = null;
            mConLast = null;
        } finally {
            conLockRelease();
        }

        while (pipe != null) {
            doClose(pipe);
            pipe = pipe.mConNext;
        }
    }

    private void doClose(CorePipe pipe) {
        cPipeVersionHandle.setOpaque(pipe, -1);
        pipe.mConPrev = null;
        pipe.mConNext = null;
        try {
            pipe.doClose();
        } catch (IOException e) {
            // Ignore.
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
