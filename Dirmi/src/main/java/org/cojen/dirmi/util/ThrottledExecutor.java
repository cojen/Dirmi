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

package org.cojen.dirmi.util;

import java.util.LinkedList;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.cojen.util.ThrowUnchecked;

/**
 * Utility class which can throttle the activity of {@link
 * org.cojen.dirmi.Asynchronous Asynchronous} methods. Without a throttling
 * mechanism, {@code Asynchronous} methods can cause too many threads to
 * execute or starve other activities.
 *
 * @author Brian S O'Neill
 */
public class ThrottledExecutor implements Executor {
    private final int mPermits;
    private final int mMaxQueued;
    private final LinkedList<Runnable> mQueue;

    private int mActive;

    /**
     * @param permits maximum number of threads allowed to execute commands concurrently
     * @param maxQueued maximum number of queued commands allowed before rejecting new ones
     */
    public ThrottledExecutor(int permits, int maxQueued) {
        if (permits < 1) {
            throw new IllegalArgumentException("Permits must be at least one: " + permits);
        }
        if (maxQueued < 0) {
            throw new IllegalArgumentException("Maximum queued cannot be negative: " + maxQueued);
        }
        mPermits = permits;
        mMaxQueued = maxQueued;
        mQueue = new LinkedList<Runnable>();
    }

    /**
     * Queues the command for later execution, or executes it immediately. If
     * the queue contains more commands, this method will attempt to drain the
     * queue before returning. If new commands are constantly being enqueued,
     * this method might never return.
     *
     * @throws RejectedExecutionException if too many commands are queued
     */
    public void execute(Runnable command) throws RejectedExecutionException {
        if (command == null) {
            throw new NullPointerException("Command is null");
        }

        LinkedList<Runnable> queue = mQueue;

        synchronized (this) {
            int active = mActive;

            if (active >= mPermits) {
                if (queue.size() >= mMaxQueued) {
                    throw new RejectedExecutionException("Too many queued commands");
                }
                queue.add(command);
                return;
            }

            if (!queue.isEmpty()) {
                queue.add(command);
                command = null;
            }

            mActive = ++active;
        }

        while (true) {
            if (command != null) {
                try {
                    command.run();
                } catch (Throwable e) {
                    try {
                        Thread t = Thread.currentThread();
                        t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    } catch (Throwable e2) {
                        // Good job, uncaught exception handler.
                        synchronized (this) {
                            if (queue.isEmpty() || mActive > 1) {
                                // Safe to exit.
                                mActive--;
                                ThrowUnchecked.fire(e2);
                                return;
                            }
                            // Cannot exit because commands need to drain, so
                            // ignore the second exception.
                        }
                    }
                }
            }

            synchronized (this) {
                if ((command = queue.poll()) == null) {
                    mActive--;
                    return;
                }
            }
        }
    }
}
