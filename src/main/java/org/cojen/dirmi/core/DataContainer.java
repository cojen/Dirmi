/*
 *  Copyright 2025 Cojen.org
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

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.cojen.dirmi.DataUnavailableException;
import org.cojen.dirmi.Pipe;

/**
 * Base class for a data container, which must be subclassed and implement a remote interface.
 *
 * @author Brian S. O'Neill
 * @see StubMaker
 */
public abstract class DataContainer {
    private static final VarHandle cLatchHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cLatchHandle = lookup.findVarHandle(DataContainer.class, "mLatch", Latch.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }
    }

    private volatile Latch mLatch;

    /**
     * Is constructed with the data latch held.
     */
    protected DataContainer() {
        cLatchHandle.setRelease(this, new Latch());
    }

    /**
     * Construct an immediately unavailable instance.
     *
     * @param unavailableReason optional String or Throwable
     */
    protected DataContainer(Object unavailableReason) {
        cLatchHandle.setRelease(this, new Unavailable(unavailableReason));
    }

    // Note: the methods defined below won't conflict with data methods because they're static
    // or the method signatures won't match.

    /**
     * Blocks until dataAvailable or dataUnavailable is called. If no exception is thrown, then
     * the data fields (defined by the subclass) are available to be read.
     */
    protected final void awaitData(DataContainer dc) throws DataUnavailableException {
        Latch latch;
        while ((latch = (Latch) cLatchHandle.getAcquire(dc)) != null) {
            latch.await();
        }
    }

    /**
     * To be called after the data fields have been set. The latch must be held when this
     * method is called, which is then released by this method.
     */
    protected static void dataAvailable(DataContainer dc) {
        // Note: Might throw NullPointerException if the latch was already released.
        ((Latch) cLatchHandle.getAndSetRelease(dc, null)).release();
    }

    /**
     * To be called after the data fields cannot be set. The latch must be held when this
     * method is called, which is then released by this method.
     *
     * @param reason optional String or Throwable
     */
    protected static void dataUnavailable(DataContainer dc, Object reason) {
        var unavailable = new Unavailable(reason);
        // Note: Might throw NullPointerException if the latch was already released.
        ((Latch) cLatchHandle.getAndSetRelease(dc, unavailable)).release();
    }

    /**
     * To be called for conditionally setting the data fields. When true is returned, the data
     * fields should be set, and then dataAvailable or dataUnavailable must be called. When
     * false is returned, the data fields are already available, or another thread is setting
     * them, and so they should be skipped.
     */
    protected static boolean relatch(DataContainer dc) {
        var latch = (Latch) cLatchHandle.getAcquire(dc);
        while (true) {
            if (latch == null || !(latch instanceof Unavailable)) {
                return false;
            }
            var witness = (Latch) cLatchHandle.compareAndExchange(dc, latch, new Latch());
            if (witness == latch) {
                return true;
            }
            latch = witness;
        }
    }

    /**
     * @param pipe pass null if data is unavailable
     */
    public abstract void readDataAndUnlatch(Pipe pipe) throws IOException;

    /**
     * @param pipe pass null if data is unavailable
     */
    public abstract void readOrSkipData(Pipe pipe) throws IOException;

    /**
     * Behaves like the BooleanLatch example in AbstractQueuedSynchronizer.
     */
    private static class Latch extends AbstractQueuedSynchronizer {
        /**
         * Wait for the latch to be released, or else throws DataUnavailableException.
         */
        void await() throws DataUnavailableException {
            acquireShared(0);
        }

        /**
         * Once released, this Latch instance cannot be used again.
         */
        final void release() {
            releaseShared(0);
        }

        @Override
        protected final int tryAcquireShared(int unused) {
            return getState() == 0 ? -1 : 1;
        }

        @Override
        protected final boolean tryReleaseShared(int unused) {
            setState(1);
            return true;
        }
    }

    private static final class Unavailable extends Latch {
        private final Object mReason;

        public Unavailable(Object reason) {
            mReason = reason;
        }

        @Override
        void await() throws DataUnavailableException {
            Object reason = mReason;
            if (reason instanceof Throwable e) {
                throw new DataUnavailableException(e);
            } else if (reason == null) {
                throw new DataUnavailableException();
            } else {
                throw new DataUnavailableException(reason.toString());
            }
        }
    }
}
