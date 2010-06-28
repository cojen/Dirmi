/*
 *  Copyright 2010 Brian S O'Neill
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

import org.cojen.util.ThrowUnchecked;

/**
 * Runnable intended for scheduling tasks which passes uncaught exceptions to
 * the current thread's uncaught exception handler. Scheduled tasks which
 * directly implement {@code Runnable} allow uncaught exceptions to be
 * swallowed by {@link java.util.concurrent.ScheduledFuture}.
 *
 * @author Brian S O'Neill
 */
public abstract class ScheduledTask<E extends Throwable> implements Runnable {
    public final void run() {
        try {
            doRun();
        } catch (Throwable e) {
            try {
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
            } catch (Throwable e2) {
                // Uncaught exception handler itself is broken. As a last
                // resort, throw exception to Future as usual.
                ThrowUnchecked.fire(e);
            }
        }
    }

    protected abstract void doRun() throws E;
}
