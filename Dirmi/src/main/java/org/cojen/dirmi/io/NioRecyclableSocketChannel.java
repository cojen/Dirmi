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

import org.cojen.dirmi.RejectedException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class NioRecyclableSocketChannel extends RecyclableSocketChannel {
    NioRecyclableSocketChannel(IOExecutor executor, NioSocketChannel channel) throws IOException {
        super(executor, channel);
    }

    NioRecyclableSocketChannel(NioRecyclableSocketChannel channel, Input in, Output out) {
        super(channel, in, out);
    }

    @Override
    protected NioRecyclableSocketChannel newRecycledChannel(Input in, Output out) {
        return new NioRecyclableSocketChannel(this, in, out);
    }

    @Override
    public boolean usesSelectNotification() {
        return true;
    }

    @Override
    public void inputNotify(Channel.Listener listener) {
        try {
            if (isInputReady()) {
                ready(listener);
            } else {
                ((NioSocketChannel) socket()).inputNotify(listener);
            }
        } catch (IOException e) {
            closed(listener, e);
        }
    }

    @Override
    public void outputNotify(final Channel.Listener listener) {
        try {
            if (isOutputReady()) {
                ready(listener);
            } else {
                ((NioSocketChannel) socket()).outputNotify(listener);
            }
        } catch (IOException e) {
            closed(listener, e);
        }
    }

    private void ready(final Channel.Listener listener) {
        try {
            executor().execute(new Runnable() {
                public void run() {
                    listener.ready();
                }
            });
        } catch (RejectedException e) {
            listener.rejected(e);
        }
    }

    private void closed(final Channel.Listener listener, final IOException cause) {
        try {
            executor().execute(new Runnable() {
                public void run() {
                    listener.closed(cause);
                }
            });
        } catch (RejectedException e) {
            listener.rejected(e);
        }
    }
}
