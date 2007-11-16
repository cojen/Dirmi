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

package dirmi.core;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ThreadFactory;

/**
 * This ThreadFactory produces threads whose name begins with "Session".
 *
 * @author Brian S O'Neill
 */
public class SessionThreadFactory implements ThreadFactory {
    static final AtomicInteger mPoolNumber = new AtomicInteger(1);
    final ThreadGroup mGroup;
    final AtomicInteger mThreadNumber = new AtomicInteger(1);
    final String mNamePrefix;
    final boolean mDaemon;
    final Thread.UncaughtExceptionHandler mHandler;

    /**
     * Create a ThreadFactory which produces threads whose name begins with
     * "Session".
     *
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     */
    public SessionThreadFactory(boolean daemon) {
        this(daemon, null, null);
    }

    /**
     * Create a ThreadFactory which produces threads whose name begins with
     * "Session-" and the given subname.
     *
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     * @param subname optional
     */
    public SessionThreadFactory(boolean daemon, String subname) {
        this(daemon, subname, null);
    }

    /**
     * Create a ThreadFactory which produces threads whose name begins with the
     * "Session-" and the given subname.
     *
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     * @param subname optional
     * @param handler optional uncaught exception handler
     */
    public SessionThreadFactory(boolean daemon, String subname,
                                Thread.UncaughtExceptionHandler handler)
    {
        SecurityManager s = System.getSecurityManager();
        mGroup = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        if (subname == null) {
            mNamePrefix = "Session-" + mPoolNumber.getAndIncrement() + "-worker-";
        } else {
            mNamePrefix = "Session-" + subname + '-' + mPoolNumber.getAndIncrement() + "-worker-";
        }
        mDaemon = daemon;
        mHandler = handler;
    }

    public Thread newThread(Runnable r) {
        Thread t = new Thread(mGroup, r, mNamePrefix + mThreadNumber.getAndIncrement(), 0);
        if (t.isDaemon() != mDaemon) {
            t.setDaemon(mDaemon);
        }
        if (t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }
        if (mHandler != null) {
            t.setUncaughtExceptionHandler(mHandler);
        }
        return t;
    }
}
