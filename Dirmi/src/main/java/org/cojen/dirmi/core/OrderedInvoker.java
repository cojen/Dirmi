/*
 *  Copyright 2009-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.io.Closeable;

import java.lang.reflect.Method;

import java.rmi.RemoteException;

import java.util.PriorityQueue;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.RejectedException;

import org.cojen.dirmi.io.IOExecutor;

import org.cojen.dirmi.util.ScheduledTask;

/**
 * Utility class used by Skeletons to implement ordered method invocation.
 *
 * @author Brian S O'Neill
 */
public class OrderedInvoker implements Closeable {
    // Maximum time a hole in the sequence is tolerated until the session is
    // forcibly closed. A hole indicates a communication failure, and if not
    // filled, the pending queue grows forever. TODO: This should tie into ping
    // failure somehow.
    // FIXME: The algorithm to detect holes is flawed. If a method invocation
    // takes a long time, it is incorrectly interpretted as a hole.
    private static final int MAX_HOLE_DURATION_MILLIS;

    static {
        int maxHoleDuration = 10000;

        try {
            String propValue = System.getProperty
                ("org.cojen.dirmi.core.OrderedInvoker.maxHoleDurationMillis");

            if (propValue != null) {
                try {
                    maxHoleDuration = Math.max(1, Integer.parseInt(propValue));
                } catch (NumberFormatException e) {
                }
            }
        } catch (SecurityException e) {
        }

        MAX_HOLE_DURATION_MILLIS = maxHoleDuration;
    }

    private final StandardSession mSession;

    private int mLastSequence;
    private PriorityQueue<SequencedOp> mPendingOps;
    private boolean mDraining;

    private Future<?> mHoleCheckTaskFuture;

    private boolean mClosed;

    OrderedInvoker(StandardSession session) {
        mSession = session;
    }

    /**
     * Is called by methods which cannot yield control of the current
     * thread. Call finished after invoking method.
     *
     * @return false if session was closed because of an unfilled hole in the ordered sequence
     */
    public synchronized boolean waitForNext(int sequence) throws InterruptedException {
        while (!isNext(sequence)) {
            if (mClosed) {
                return false;
            }
            wait(MAX_HOLE_DURATION_MILLIS);
        }
        return true;
    }

    /**
     * Is called by asynchronous methods to prevent too many threads from
     * accumulating. If false is returned, call addPendingMethod. If true is
     * returned, call finished after invoking asynchronous method.
     */
    public boolean isNext(int sequence) {
        try {
            synchronized (this) {
                if (mLastSequence + 1 == sequence) {
                    if (mHoleCheckTaskFuture != null) {
                        mHoleCheckTaskFuture.cancel(false);
                        mHoleCheckTaskFuture = null;
                    }
                    return true;
                }

                if (mHoleCheckTaskFuture == null && !mClosed) {
                    // There's a hole in the sequence, and so close the session
                    // if not filled in time.
                    mHoleCheckTaskFuture = new HoleCheckTask().schedule(mSession.mExecutor);
                }
            }
        } catch (RejectedException e) {
            // Close outside of synchronized block to allow it to block.
            close(true);
        }

        return false;
    }

    public void finished(int sequence) {
        SequencedOp op;
        synchronized (this) {
            if (mClosed) {
                return;
            }

            if (isNext(sequence)) {
                mLastSequence = sequence;
                notifyAll();
            } else {
                // Finished out of order, which could happen if an exception
                // was thrown. Inject a no-op to fill the gap.
                addPendingOp(new SequencedOp(sequence));
            }

            if ((op = shouldDrainOps()) == null) {
                return;
            }
        }

        drainPendingOps(op);
    }

    public void addPendingMethod(int sequence,
                                 final Method method,
                                 final Object instance,
                                 final Object[] params)
    {
        SequencedOp op;
        synchronized (this) {
            if (mClosed) {
                // Throw it away since it will never be executed.
                return;
            }

            addPendingOp(new SequencedOp(sequence) {
                public void apply() {
                    try {
                        method.invoke(instance, params);
                    } catch (Throwable e) {
                        uncaughtException(e);
                    }
                }
            });

            if ((op = shouldDrainOps()) == null) {
                return;
            }
        }

        drainPendingOps(op);
    }

    /**
     * Call if method returns a Future or Completion.
     */
    public <V> void addPendingMethod(int sequence,
                                     final Method method,
                                     final Object instance,
                                     final Object[] params,
                                     final SkeletonSupport support,
                                     final RemoteCompletion<V> completion)
    {
        SequencedOp op;
        synchronized (this) {
            if (mClosed) {
                // Throw it away since it will never be executed.
                return;
            }

            addPendingOp(new SequencedOp(sequence) {
                public void apply() {
                    try {
                        Future<V> response = (Future<V>) method.invoke(instance, params);
                        support.completion(response, completion);
                    } catch (Throwable e) {
                        try {
                            completion.exception(e);
                        } catch (RemoteException e2) {
                            // Ignore; session is broken.
                        }
                    }
                }
            });

            if ((op = shouldDrainOps()) == null) {
                return;
            }
        }

        drainPendingOps(op);
    }

    @Override
    public void close() {
        // Close not a failure.
        close(false);
    }

    void uncaughtException(Throwable e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            cause = e;
        }

        try {
            Thread t = Thread.currentThread();
            t.getUncaughtExceptionHandler().uncaughtException(t, cause);
        } catch (Throwable e2) {
            // I give up.
        }
    }

    private void addPendingOp(SequencedOp op) {
        if (mPendingOps == null) {
            mPendingOps = new PriorityQueue<SequencedOp>();
        }
        mPendingOps.add(op);
    }

    private SequencedOp shouldDrainOps() {
        if (mDraining) {
            return null;
        }
        mDraining = true;
        return nextOp();
    }

    private void drainPendingOps(SequencedOp op) {
        while (true) {
            try {
                op.apply();
            } finally {
                synchronized (this) {
                    if (isNext(op.mSequence)) {
                        mLastSequence = op.mSequence;
                        notifyAll();
                    }
                    if ((op = nextOp()) == null) {
                        return;
                    }
                }
            }
        }
    }

    private SequencedOp nextOp() {
        if (mPendingOps != null) {
            if (mPendingOps.isEmpty()) {
                mPendingOps = null;
            } else if (isNext(mPendingOps.peek().mSequence)) {
                return mPendingOps.poll();
            }
        }
        mDraining = false;
        return null;
    }
 
    void holeCheck(Future<?> taskFuture) {
        synchronized (this) {
            if (taskFuture == null || mHoleCheckTaskFuture != taskFuture) {
                // Stale task.
                return;
            }
        }

        // Hole not filled in time.
        close(true);
    }

    private void close(boolean onFailure) {
        synchronized (this) {
            mClosed = true;
            mPendingOps = null;
            mDraining = false;

            if (mHoleCheckTaskFuture != null) {
                mHoleCheckTaskFuture.cancel(false);
                mHoleCheckTaskFuture = null;
            }

            notifyAll();
        }

        if (onFailure) {
            // Close session outside of synchronized block to allow it to block.
            mSession.close("Closing session after waiting " +
                           MAX_HOLE_DURATION_MILLIS +
                           " milliseconds for missing ordered method invocation");
        }
    }

    private static class SequencedOp implements Comparable<SequencedOp> {
        final int mSequence;

        SequencedOp(int sequence) {
            mSequence = sequence;
        }

        /**
         * Override method to do something.
         */
        void apply() {
        }

        @Override
        public int compareTo(SequencedOp op) {
            // Subtract for modulo arithmetic difference.
            return mSequence - op.mSequence;
        }
    }

    private class HoleCheckTask extends ScheduledTask<RuntimeException> {
        private Future<?> mFuture;

        Future<?> schedule(IOExecutor executor) throws RejectedException {
            return mFuture = executor.schedule
                (this, MAX_HOLE_DURATION_MILLIS, TimeUnit.MILLISECONDS);
        }

        @Override
        protected void doRun() {
            holeCheck(mFuture);
        }
    }
}
