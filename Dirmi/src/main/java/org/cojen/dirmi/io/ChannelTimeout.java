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

package org.cojen.dirmi.io;

import java.io.IOException;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.RemoteTimeoutException;

import org.cojen.dirmi.util.ScheduledTask;
import org.cojen.dirmi.util.Timer;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class ChannelTimeout extends ScheduledTask<RuntimeException> {
    private final Channel mChannel;
    private final Timer mTimer;
    private final Future<?> mFuture;

    // 1: don't cancel, 2: cancelled
    private int mState;

    ChannelTimeout(IOExecutor executor, Channel channel, long timeout, TimeUnit unit)
        throws IOException
    {
        this(executor, channel, new Timer(timeout, unit));
    }

    ChannelTimeout(IOExecutor executor, Channel channel, Timer timer) throws IOException {
        mChannel = channel;
        mTimer= timer;
        mFuture = executor.schedule
            (this, RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    public void cancel() throws RemoteTimeoutException {
        synchronized (this) {
            if (mState == 2) {
                throw new RemoteTimeoutException(mTimer);
            }
            mState = 1;
        }
        mFuture.cancel(false);
    }

    protected void doRun() {
        synchronized (this) {
            if (mState != 0) {
                return;
            }
            mState = 2;
        }
        mChannel.disconnect();
    }
}
