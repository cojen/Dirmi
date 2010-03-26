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
 * ClientPipe starts in writing mode, and a read switches it to reading mode.
 * After fully reading all remaining input, calling close allows the pipe to be
 * recycled.
 *
 * @author Brian S O'Neill
 */
abstract class ClientPipe extends WrappedPipe {
    private static final int WRITING = 0, READING = 1, CLOSED = 2;

    private static final AtomicIntegerFieldUpdater<ClientPipe> cStateUpdater =
        AtomicIntegerFieldUpdater.newUpdater(ClientPipe.class, "mState");

    private final InvocationChannel mChannel;

    private volatile int mState;

    ClientPipe(InvocationChannel channel) {
        mChannel = channel;
    }

    @Override
    public void close() throws IOException {
        int oldState = cStateUpdater.getAndSet(this, CLOSED);
        if (oldState == CLOSED) {
            return;
        }
        if (!cancelTimeout()) {
            // Channel will close or already has.
            return;
        }
        if (oldState == WRITING) {
            if (mChannel.outputSuspend()) {
                mChannel.reset();
            } else {
                mChannel.close();
                return;
            }
        }
        tryInputResume(mChannel);
    }

    @Override
    Pipe pipeForRead() throws IOException {
        InvocationChannel channel = mChannel;
        if (mState == READING) {
            return channel;
        }
        if (cStateUpdater.compareAndSet(this, WRITING, READING)) {
            channel.outputSuspend();
            channel.reset();
            return channel;
        }
        if (mState == READING) {
            return channel;
        }
        throw new IOException("Pipe is closed");
    }

    @Override
    Pipe pipeForWrite() throws IOException {
        if (mState == WRITING) {
            return mChannel;
        }
        anyPipe(); // check if closed
        throw new IOException("Pipe is not in a writable state");
    }

    @Override
    Pipe anyPipe() throws IOException {
        if (mState != CLOSED) {
            return mChannel;
        }
        throw new IOException("Pipe is closed");
    }

    /**
     * Called when channel can be used for new client requests, if input can be
     * resumed. Channel should be closed otherwise.
     */
    abstract void tryInputResume(InvocationChannel channel);
}
