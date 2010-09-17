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

import java.io.Closeable;
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
class Waiter<C extends Closeable> {
    private static final int
        AVAILABLE = 1, CLOSED = 2, INTERRUPTED = 3, TIMEOUT = 4, EXCEPTION = 5;

    static <C extends Closeable> Waiter<C> create() {
        return new Waiter<C>();
    }

    private C mObject;
    private IOException mException;
    private int mState;
    private long mTimeout;
    private TimeUnit mTimeoutUnit;

    private Waiter() {
    }

    public void available(C object) {
        synchronized (this) {
            notify();
            if (mState == 0) {
                mObject = object;
                mState = AVAILABLE;
                return;
            }
        }

        try {
            object.close();
        } catch (IOException e) {
        }
    }

    public synchronized void rejected(RejectedException e) {
        notify();
        if (mState == 0) {
            mException = e;
            mState = EXCEPTION;
        }
    }

    public synchronized void failed(IOException e) {
        notify();
        if (mState == 0) {
            mException = e;
            mState = EXCEPTION;
        }
    }

    public synchronized void closed(IOException e) {
        notify();
        if (mState == 0) {
            mState = CLOSED;
        }
    }

    public synchronized C waitFor() throws IOException {
        while (true) {
            C object = check();
            if (object != null) {
                return object;
            }
            try {
                wait();
            } catch (InterruptedException e) {
                return interrupted();
            }
        }
    }

    public synchronized C waitFor(final long timeout, final TimeUnit unit) throws IOException {
        long timeoutNanos = unit.toNanos(timeout);
        long start = System.nanoTime();

        while (timeoutNanos > 0) {
            C object = check();
            if (object != null) {
                return object;
            }

            long now = System.nanoTime();
            timeoutNanos -= now - start;
            start = now;

            try {
                wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeoutNanos)));
            } catch (InterruptedException e) {
                return interrupted();
            }
        }

        if (mState == 0) {
            mState = TIMEOUT;
            mTimeout = timeout;
            mTimeoutUnit = unit;
        }

        return check();
    }

    private C check() throws IOException {
        switch (mState) {
        case AVAILABLE:
            return mObject;
        case CLOSED:
            throw new ClosedException();
        case INTERRUPTED:
            throw new InterruptedIOException();
        case TIMEOUT:
            throw new RemoteTimeoutException(mTimeout, mTimeoutUnit);
        case EXCEPTION:
            mException.fillInStackTrace();
            throw mException;
        }

        return null;
    }

    private C interrupted() throws IOException {
        if (mState == 0) {
            mState = INTERRUPTED;
        }
        return check();
    }
}
