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

package org.cojen.dirmi.core;

import java.io.Serializable;

import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class SessionExchanger {
    private Object mObject;
    private RemoteCompletion<Object> mCallback;

    SessionExchanger() {
    }

    /**
     * @param timeout pass negative for infinite
     * @return false if timed out
     */
    public synchronized boolean enqueue(Object obj, long timeout, TimeUnit unit)
        throws RemoteException, InterruptedException
    {
        if (obj == null) {
            obj = new Null();
        }

        if (timeout < 0) {
            while (mObject != null) {
                wait();
            }
        } else {
            long timeoutMillis = unit.toMillis(timeout);

            if (timeoutMillis > 0) {
                long endNanos = System.nanoTime() + unit.toNanos(timeout);
                while (mObject != null) {
                    wait(timeoutMillis);
                    if (mObject == null) {
                        break;
                    }
                    long timeoutNanos = endNanos - System.nanoTime();
                    if (timeoutNanos < 1000000) {
                        break;
                    }
                    timeoutMillis = timeoutNanos / 1000000;
                }
            }

            if (mObject != null) {
                return false;
            }
        }

        if (mCallback == null) {
            mObject = obj;
        } else {
            mCallback.complete(obj);
            mCallback = null;
        }

        return true;
    }

    /**
     * @param callback optional callback
     * @return object or Null if available, otherwise callback will be invoked
     * later if provided
     * @throws IllegalStateException if another callback is registered
     */
    public synchronized Object dequeue(RemoteCompletion<Object> callback) {
        if (callback != null && mCallback != null) {
            throw new IllegalStateException();
        }
        if (mObject != null) {
            Object obj = mObject;
            mObject = null;
            notify();
            return obj;
        } else {
            mCallback = callback;
            return null;
        }
    }

    public static class Null implements Serializable {}
}
