/*
 *  Copyright 2008-2010 Brian S O'Neill
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

import java.rmi.RemoteException;

import java.util.Queue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.Completion;
import org.cojen.dirmi.Unreferenced;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class RemoteCompletionServer<V> implements Completion<V>, RemoteCompletion<V>, Unreferenced {
    private V mValue;
    private Throwable mComplete;
    private Queue<? super Completion<V>> mQueue;

    RemoteCompletionServer() {
    }

    @Override
    public synchronized void register(Queue<? super Completion<V>> completionQueue) {
        if (completionQueue == null) {
            throw new IllegalArgumentException("Completion queue is null");
        }
        if (mQueue != null) {
            throw new IllegalStateException("Already registered with a completion queue");
        }
        mQueue = completionQueue;
        if (mComplete != null) {
            completionQueue.add(this);
        }
    }

    @Override
    public boolean cancel(boolean mayInterrupt) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public synchronized boolean isDone() {
        return mComplete != null;
    }

    @Override
    public synchronized V get() throws InterruptedException, ExecutionException {
        Throwable complete;
        while ((complete = mComplete) == null) {
            wait();
        }
        if (complete == Complete.THE) {
            return mValue;
        }
        throw new ExecutionException(complete);
    }
    
    @Override
    public synchronized V get(final long timeout, final TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException
    {
        long timeoutMillis = unit.toMillis(timeout);

        Throwable complete = mComplete;

        if (complete == null && timeoutMillis > 0) {
            long endNanos = System.nanoTime() + unit.toNanos(timeout);
            while (true) {
                wait(timeoutMillis);
                if ((complete = mComplete) != null) {
                    break;
                }
                long timeoutNanos = endNanos - System.nanoTime();
                if (timeoutNanos < 1000000) {
                    break;
                }
                timeoutMillis = timeoutNanos / 1000000;
            }
        }

        if (complete == null) {
            throw new TimeoutException("" + timeout + ' ' + unit);
        }
        if (complete == Complete.THE) {
            return mValue;
        }
        throw new ExecutionException(complete);
    }

    @Override
    public synchronized void complete(V value) {
        if (mComplete == null) {
            mValue = value;
            mComplete = Complete.THE;
            done();
        }
    }

    @Override
    public synchronized void exception(Throwable cause) {
        if (mComplete == null) {
            if (cause == null) {
                cause = new NullPointerException("Exception cause is null");
            }
            mComplete = cause;
            done();
        }
    }

    @Override
    public synchronized void unreferenced() {
        if (mComplete == null) {
            mComplete = new RemoteException("Session closed");
            done();
        }
    }

    // Caller must be synchronized.
    private void done() {
        notifyAll();
        if (mQueue != null) {
            mQueue.add(this);
        }
    }

    private static class Complete extends Throwable {
        static final Complete THE = new Complete();

        private Complete() {
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
