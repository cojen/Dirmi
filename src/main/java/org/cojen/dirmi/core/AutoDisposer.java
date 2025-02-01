/*
 *  Copyright 2024 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public final class AutoDisposer extends ReferenceQueue<Stub> implements Runnable {
    private static AutoDisposer cInstance;

    private static AutoDisposer access() {
        AutoDisposer instance;
        synchronized (AutoDisposer.class) {
            instance = cInstance;
            if (instance == null) {
                cInstance = instance = new AutoDisposer();
            }
            instance.mRegistered++;
        }
        return instance;
    }

    private long mRegistered;

    private AutoDisposer() {
        Thread t = new Thread(this, getClass().getSimpleName());
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        // Infinite timeout.
        long timeout = 0;

        while (true) {
            Reference<? extends Stub> ref;
            try {
                ref = remove(timeout);
            } catch (InterruptedException e) {
                // Clear the interrupted status.
                Thread.interrupted();
                ref = null;
            }

            if (ref == null) {
                // Timed out waiting, which implies that the timeout isn't infinite.
                synchronized (AutoDisposer.class) {
                    if (mRegistered == 0) {
                        // Still idle, so exit.
                        if (cInstance == this) {
                            cInstance = null;
                        }
                        return;
                    }
                }
                continue;
            }

            long removed = 1;

            while (true) {
                if (ref instanceof BasicRef br) {
                    br.removed();
                }
                ref = poll();
                if (ref == null) {
                    break;
                }
                removed++;
            }

            synchronized (AutoDisposer.class) {
                if ((mRegistered -= removed) == 0) {
                    // If still idle after one minute, then exit.
                    timeout = 60_000;
                } else {
                    // Queue still has registered refs, so use an infinite timeout. There's no
                    // point in waking up this thread unless it has something to do.
                    timeout = 0;
                }
            }
        }
    }

    public static sealed class BasicRef extends WeakReference<StubWrapper> {
        private final StubInvoker mInvoker;

        public BasicRef(StubWrapper wrapper, StubInvoker invoker) {
            super(wrapper, access());
            mInvoker = invoker;
        }

        void removed() {
            if (mInvoker.support().trySession() instanceof CoreSession session) {
                // Note: Although the pipe isn't flushed immediately, this operation might
                // still block. If it does, then no dispose messages will be sent for any
                // sessions until the blocked one automatically disconnects. This can be
                // prevented by running a task in a separate thread, but that would end up
                // creating a new temporary object. Ideally, the task option should only be
                // used when the pipe's output buffer is full.
                session.stubDisposeAndNotify(mInvoker, null, false);
            }
        }
    }

    public static final class CountedRef extends BasicRef {
        private static final VarHandle cRefCountHandle;

        static {
            try {
                var lookup = MethodHandles.lookup();
                cRefCountHandle = lookup.findVarHandle(CountedRef.class, "mRefCount", long.class);
            } catch (Throwable e) {
                throw CoreUtils.rethrow(e);
            }
        }

        private long mRefCount;

        public CountedRef(StubWrapper wrapper, StubInvoker invoker) {
            super(wrapper, invoker);
            mRefCount = 1;
        }

        @Override
        void removed() {
            decRefCount(1);
        }

        public void incRefCount() {
            cRefCountHandle.getAndAdd(this, 1L);
        }

        /**
         * Note: Calling this method might block if a notification needs to be written.
         */
        public void decRefCount(long amount) {
            if (((long) cRefCountHandle.getAndAdd(this, -amount)) <= amount) {
                super.removed();
            }
        }
    }
}
