/*
 *  Copyright 2007 Brian S O'Neill
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

package dirmi.core2;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;

/**
 * PhantomReference which has a callback which is invoked when reference
 * becomes unreachable.
 *
 * @author Brian S O'Neill
 */
abstract class UnreachableReference<T> extends PhantomReference<T> {
    static final ReferenceQueue<Object> cQueue;

    static {
        cQueue = new ReferenceQueue<Object>();
        new Cleaner().start();
    }

    public UnreachableReference(T referent) {
        super(referent, cQueue);
    }

    /**
     * Callback invoked when reference becomes unreachable. Implementation
     * should perform any necessary cleanup, but it should do so without
     * blocking.
     */
    protected abstract void unreachable();

    private static class Cleaner extends Thread {
        Cleaner() {
            super("Unreachable reference cleaner");
            setDaemon(true);
        }

        public void run() {
            while (true) {
                Reference<?> ref;
                try {
                    ref = cQueue.remove();
                } catch (InterruptedException e) {
                    return;
                }

                try {
                    ((UnreachableReference) ref).unreachable();
                } catch (Throwable e) {
                    Thread t = Thread.currentThread();
                    try {
                        t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    } catch (Throwable e2) {
                    }
                }

                ref = null;
            }
        }
    }
}
