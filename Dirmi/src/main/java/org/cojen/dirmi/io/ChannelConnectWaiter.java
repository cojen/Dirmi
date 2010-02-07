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

package org.cojen.dirmi.io;

import java.io.IOException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.RejectedException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class ChannelConnectWaiter implements ChannelConnector.Listener {
    private final Waiter<Channel> mWaiter = Waiter.create();

    public void connected(Channel channel) {
        mWaiter.available(channel);
    }

    public void rejected(RejectedException e) {
        mWaiter.rejected(e);
    }

    public void failed(IOException e) {
        mWaiter.failed(e);
    }

    public Channel waitForChannel() throws IOException {
        return mWaiter.waitFor();
    }

    public Channel waitForChannel(long timeout, TimeUnit unit) throws IOException {
        return mWaiter.waitFor(timeout, unit);
    }
}
