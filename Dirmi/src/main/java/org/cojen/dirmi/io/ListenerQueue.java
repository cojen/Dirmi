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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.util.Deque;
import java.util.LinkedList;

import java.util.concurrent.TimeUnit;

import org.cojen.util.ThrowUnchecked;

import org.cojen.dirmi.RejectedException;

/**
 * Allows a single method to be invoked on enqueued listeners. If no listeners
 * have been enqueued, then the invocation is performed later. Listener must be
 * defined by an interface, and all methods must return void.
 *
 * @author Brian S O'Neill
 */
class ListenerQueue<L> {
    private final IOExecutor mExecutor;
    private final Constructor<L> mListenerProxyCtor;
    private final Deque<L> mListenerQueue;
    private final Deque<Handoff> mHandoffQueue;

    ListenerQueue(IOExecutor executor, Class<L> listenerType) {
        mExecutor = executor;

        try {
            mListenerProxyCtor = (Constructor<L>) Proxy
                .getProxyClass(listenerType.getClassLoader(), listenerType)
                .getConstructor(InvocationHandler.class);
        } catch (NoSuchMethodException e) {
            throw new Error(e);
        }

        mListenerQueue = new LinkedList<L>();
        mHandoffQueue = new LinkedList<Handoff>();
    }

    /**
     * Enqueues the listener to be invoked by a separate thread. If a
     * RejectedException is thrown, no enqueued listeners or events are
     * lost. To deliver the exception to a listener, use the dequeue method.
     *
     * @throws RejectedException if no available threads
     */
    public void enqueue(L listener) throws RejectedException {
        final Handoff handoff;
        final L handoffListener;

        synchronized (this) {
            mListenerQueue.add(listener);
            handoff = mHandoffQueue.poll();
            if (handoff == null) {
                return;
            }
            handoffListener = mListenerQueue.remove();
        }

        Runnable command = new Runnable() {
            public void run() {
                if (handoff.handoff(handoffListener)) {
                    enqueue(handoff);
                }
            }
        };

        try {
            mExecutor.execute(command);
        } catch (RejectedException e) {
            // Try to schedule as soon as a thread becomes available.
            try {
                mExecutor.schedule(command, 0, TimeUnit.SECONDS);
                return;
            } catch (RejectedException e2) {
                // At least we tried.
            }

            synchronized (this) {
                // Don't lose these. Note that this does not guarantee that
                // events are delivered in order.
                mHandoffQueue.addFirst(handoff);
                mListenerQueue.addFirst(handoffListener);
            }

            throw e;
        }
    }

    /**
     * Returns a listener which allows one method to be invoked on it. Delivery
     * to an actual listener can be synchronous or asynchronous.
     *
     * @param asynchronous pass true to ensure that invocation is always
     * performed in a separate thread
     */
    public L dequeue() {
        synchronized (this) {
            L listener = mListenerQueue.poll();
            if (listener != null) {
                return listener;
            }
        }
        try {
            return mListenerProxyCtor.newInstance(new Handoff());
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    /**
     * Closes this queue and allows a method to be called on all queued
     * listeners and all newly enqueued listeners. Delivery to actual
     * listeners can be synchronous or asynchronous.
     */
    public L dequeueForClose() {
        try {
            return mListenerProxyCtor.newInstance(new Closer());
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    void enqueue(Handoff handoff) {
        L listener;
        do {
            synchronized (this) {
                mHandoffQueue.add(handoff);
                listener = mListenerQueue.poll();
                if (listener == null) {
                    return;
                }
                handoff = mHandoffQueue.remove();
            }
        } while (handoff.handoff(listener));
    }

    private class Handoff implements InvocationHandler {
        private Method mMethod;
        private Object[] mArgs;

        public Object invoke(Object proxy, Method method, Object[] args) {
            synchronized (this) {
                if (mMethod != null) {
                    throw new IllegalStateException("Already invoked listener");
                }
                mMethod = method;
                mArgs = args;
            }
            enqueue(this);
            return null;
        }

        /**
         * @return true if should enqueue again
         */
        boolean handoff(L listener) {
            Method method;
            Object[] args;
            synchronized (this) {
                method = mMethod;
                args = mArgs;
            }
            try {
                method.invoke(listener, args);
            } catch (IllegalAccessException e) {
                throw new Error(e);
            } catch (InvocationTargetException e) {
                ThrowUnchecked.fireCause(e);
            }
            return false;
        }
    }

    private class Closer extends Handoff {
        @Override
        boolean handoff(L listener) {
            super.handoff(listener);
            return true;
        }
    }
}
