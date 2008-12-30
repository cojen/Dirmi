/*
 *  Copyright 2008 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.lang.ref.WeakReference;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class AbstractPingTask<B> implements Runnable {
    private final WeakReference<B> mBroker;
    private volatile ScheduledFuture<?> mFuture;

    AbstractPingTask(B broker) {
        mBroker = new WeakReference<B>(broker);
    }

    /**
     * Call this method after task has been scheduled in order for it to
     * gracefully cancel itself if broker reference is cleared.
     */
    void setFuture(ScheduledFuture<?> future) {
        mFuture = future;
    }

    /**
     * Call to access broker, but returns null if cleared.
     */
    protected B broker() {
        B broker = mBroker.get();
        if (broker == null) {
            // Cancel ourself.
            ScheduledFuture<?> future = mFuture;
            if (future != null) {
                future.cancel(true);
            } else {
                // Brute force cancellation of task.
                throw new CancellationException();
            }
        }
        return broker;
    }
}
