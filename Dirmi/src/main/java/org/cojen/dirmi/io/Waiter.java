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

import java.io.InterruptedIOException;
import java.io.IOException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;

/**
 * Allows listeners to be defined which can be used by blocking calls.
 *
 * @author Brian S O'Neill
 */
class Waiter<T> {
    static <T> Waiter<T> create() {
        return new Waiter<T>();
    }

    private T mObject;
    private IOException mException;
    private boolean mClosed;

    private Waiter() {
    }

    public synchronized void available(T object) {
        mObject = object;
        notify();
    }

    public synchronized void rejected(RejectedException e) {
        mException = e;
        notify();
    }

    public synchronized void failed(IOException e) {
        mException = e;
        notify();
    }

    public synchronized void closed(IOException e) {
        mException = e;
        mClosed = true;
        notify();
    }

    public synchronized T waitFor() throws IOException {
        while (true) {
            T object = check();
            if (object != null) {
                return object;
            }
            try {
                wait();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }
    }

    public synchronized T waitFor(long timeout, TimeUnit unit) throws IOException {
        long timeoutNanos = unit.toNanos(timeout);
        long start = System.nanoTime();

        while (timeoutNanos > 0) {
            T object = check();
            if (object != null) {
                return object;
            }

            long now = System.nanoTime();
            timeoutNanos -= now - start;
            start = now;

            try {
                wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeoutNanos)));
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }

        throw new RemoteTimeoutException(timeout, unit);
    }

    private T check() throws IOException {
        if (mObject != null) {
            return mObject;
        }
        if (mException != null) {
            throw mException;
        }
        if (mClosed) {
            throw new ClosedException();
        }
        return null;
    }
}
