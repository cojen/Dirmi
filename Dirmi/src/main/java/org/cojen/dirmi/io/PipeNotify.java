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

import org.cojen.dirmi.RejectedException;

/**
 * Used by PipedInputStream and PipedChannel for asynchronous event delivery.
 *
 * @author Brian S O'Neill
 */
class PipeNotify implements Runnable {
    private final Channel.Listener mListener;
    private final IOException mException;

    /**
     * Pipe is ready.
     */
    PipeNotify(IOExecutor executor, Channel.Listener listener) {
        this(executor, listener, null);
    }

    /**
     * Pipe is closed.
     */
    PipeNotify(IOExecutor executor, Channel.Listener listener, IOException exception) {
        mListener = listener;
        mException = exception;
        try {
            executor.execute(this);
        } catch (RejectedException e) {
            listener.rejected(e);
        }
    }

    public void run() {
        if (mException == null) {
            mListener.ready();
        } else {
            mListener.closed(mException);
        }
    }
}
