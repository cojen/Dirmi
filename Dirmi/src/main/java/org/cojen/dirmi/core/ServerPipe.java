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

package org.cojen.dirmi.core;

import java.io.IOException;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.cojen.dirmi.Pipe;

/**
 * ServerPipe starts in reading mode, and a write switches it to writing mode.
 * If no input remains, calling close allows the pipe to be recycled.
 *
 * @author Brian S O'Neill
 */
abstract class ServerPipe extends WrappedPipe {
    private static final int READING = 0, WRITING = 1, CLOSED = 2;

    private static final AtomicIntegerFieldUpdater<ServerPipe> cStateUpdater =
        AtomicIntegerFieldUpdater.newUpdater(ServerPipe.class, "mState");

    private final InvocationChannel mChannel;

    private volatile int mState;

    ServerPipe(InvocationChannel channel) {
        mChannel = channel;
    }

    @Override
    public void close() throws IOException {
        if (cStateUpdater.getAndSet(this, CLOSED) == CLOSED) {
            return;
        }
        if (!cancelTimeout()) {
            // Channel will close or already has.
            return;
        }
        InvocationChannel channel = mChannel;
        if (channel.outputSuspend()) {
            channel.reset();
        } else {
            channel.close();
            return;
        }
        tryInputResume(channel);
    }

    @Override
    Pipe pipeForRead() throws IOException {
        if (mState == READING) {
            return mChannel;
        }
        anyPipe(); // check if closed
        throw new IOException("Pipe is not in a readable state");
    }

    @Override
    Pipe pipeForWrite() throws IOException {
        // Note: The check for mState multiple times is intentional, to account
        // for concurrent modification.
        if (mState == WRITING || cStateUpdater.compareAndSet(this, READING, WRITING)
            || mState == WRITING)
        {
            return mChannel;
        }
        throw new IOException("Pipe is closed");
    }

    @Override
    Pipe anyPipe() throws IOException {
        if (mState != CLOSED) {
            return mChannel;
        }
        throw new IOException("Pipe is closed");
    }

    /**
     * Called when channel can be used for new server requests, if input can be
     * resumed. Channel should be closed otherwise.
     */
    abstract void tryInputResume(InvocationChannel channel);
}
