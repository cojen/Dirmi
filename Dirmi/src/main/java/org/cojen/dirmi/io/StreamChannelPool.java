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

import java.io.Closeable;
import java.io.IOException;

import java.util.LinkedList;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class StreamChannelPool implements Recycler<StreamChannel>, Closeable {
    private static final int POLL_RATE_MILLIS = 5000;
    private static final int MAX_IDLE_MILLIS = 60 * 1000;

    private final ScheduledExecutorService mExecutor;
    private final LinkedList<Entry> mPool;

    private ScheduledFuture<?> mCloseTask;

    private boolean mClosed;

    StreamChannelPool(ScheduledExecutorService executor) {
        mExecutor = executor;
        mPool = new LinkedList<Entry>();
    }

    public synchronized void recycled(StreamChannel channel) {
        if (mClosed) {
            channel.disconnect();
            return;
        }

        mPool.add(new Entry(channel));

        if (mCloseTask == null) {
            Runnable task = new Runnable() {
                public void run() {
                    closeIdleChannels();
                }
            };
            try {
                mCloseTask = mExecutor.scheduleWithFixedDelay
                    (task, MAX_IDLE_MILLIS, POLL_RATE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                channel.disconnect();
            }
        }
    }

    public void close() {
        LinkedList<Entry> pool;
        synchronized (this) {
            mClosed = true;
            pool = new LinkedList<Entry>(mPool);
            mPool.clear();
        }
        for (Entry entry : pool) {
            entry.mChannel.disconnect();
        }
    }

    StreamChannel dequeue() {
        synchronized (this) {
            if (mPool.size() > 0) {
                return mPool.removeLast().mChannel;
            }
        }
        return null;
    }

    void closeIdleChannels() {
        ScheduledFuture<?> closeTask;

        while (true) {
            Entry entry;
            synchronized (this) {
                entry = mPool.peek();
                if (entry == null) {
                    closeTask = mCloseTask;
                    mCloseTask = null;
                    break;
                }
                long age = System.currentTimeMillis() - entry.mTimestamp;
                if (age < MAX_IDLE_MILLIS) {
                    return;
                }
                mPool.remove();
            }
            entry.mChannel.disconnect();
        }

        if (closeTask != null) {
            closeTask.cancel(false);
        }
    }

    private static class Entry {
        final StreamChannel mChannel;
        final long mTimestamp;
        
        Entry(StreamChannel channel) {
            mChannel = channel;
            mTimestamp = System.currentTimeMillis();
        }
    }
}
