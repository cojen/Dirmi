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

import java.lang.reflect.Method;

import java.rmi.RemoteException;

import java.util.PriorityQueue;

import java.util.concurrent.Future;

import org.cojen.dirmi.MalformedRemoteObjectException;
import org.cojen.dirmi.UnimplementedMethodException;

/**
 * Utility class used by Skeletons to implement ordered method invocation.
 *
 * @author Brian S O'Neill
 */
public class OrderedInvoker {
    private int mLastSequence;
    private PriorityQueue<SequencedOp> mPendingOps;
    private boolean mDraining;

    OrderedInvoker() {
    }

    /**
     * Is called by methods which cannot yield control of the current
     * thread. Call finished after invoking method.
     */
    public synchronized void waitForNext(int sequence) throws InterruptedException {
        while (!isNext(sequence)) {
            wait();
        }
    }

    /**
     * Is called by asynchronous methods to prevent too many threads from
     * accumulating. If false is returned, call addPendingMethod. If true is
     * returned, call finished after invoking asynchronous method.
     */
    public synchronized boolean isNext(int sequence) {
        return mLastSequence + 1 == sequence;
    }

    public void finished(int sequence) {
        SequencedOp op;
        synchronized (this) {
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
 
    private class SequencedOp implements Comparable<SequencedOp> {
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
}
