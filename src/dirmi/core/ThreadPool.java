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

import java.util.Collections;
import java.util.List;
import java.util.LinkedList;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom thread pool implementation which performs better than the default
 * JDK1.6 thread pool. A test program using the StandardSession and the default
 * thread pool performed was about 10,040 messages per second. With this thread
 * pool, the performance was about 10,440 messages per second. The overall CPU
 * utilization was lower too, by about 10%.
 *
 * @author Brian S O'Neill
 */
public class ThreadPool extends AbstractExecutorService {
    static final Runnable SHUTDOWN = new Runnable() {
        public void run() {
            throw new ThreadDeath();
        }
    };

    private static final AtomicInteger cPoolNumber = new AtomicInteger(1);

    private final ThreadGroup mGroup;
    private final AtomicInteger mThreadNumber = new AtomicInteger(1);
    private final String mNamePrefix;
    private final boolean mDaemon;
    private final Thread.UncaughtExceptionHandler mHandler;

    private final int mMax;
    private final long mIdleTimeout = 60000;

    // Pool is accessed like a stack.
    private final LinkedList<PooledThread> mPool;

    private int mActive;
    private boolean mShutdown;

    /**
     * @param max the maximum allowed number of threads
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     */
    public ThreadPool(int max, boolean daemon) {
        this(max, daemon, null, null);
    }

    /**
     * @param max the maximum allowed number of threads
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     * @param prefix thread name prefix; default used if null
     */
    public ThreadPool(int max, boolean daemon, String prefix) {
        this(max, daemon, prefix, null);
    }

    /**
     * @param max the maximum allowed number of threads
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     * @param prefix thread name prefix; default used if null
     * @param handler optional uncaught exception handler
     */
    public ThreadPool(int max, boolean daemon, String prefix,
                      Thread.UncaughtExceptionHandler handler)
    {
        if (max <= 0) {
            throw new IllegalArgumentException
                ("Maximum number of threads must be greater than zero: " + max);
        }

        SecurityManager s = System.getSecurityManager();
        mGroup = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        if (prefix == null) {
            prefix = "pool";
        }
        mNamePrefix = prefix + '-' + cPoolNumber.getAndIncrement() + "-thread-";
        mDaemon = daemon;
        mHandler = handler;

        mMax = max;
        mPool = new LinkedList<PooledThread>();
    }

    public void execute(Runnable command) throws RejectedExecutionException {
        PooledThread thread;

        while (true) {
            find: {
                synchronized (mPool) {
                    if (mShutdown) {
                        throw new RejectedExecutionException("Thread pool is shutdown");
                    }
                    if (mPool.size() > 0) {
                        thread = mPool.removeLast();
                        break find;
                    }
                    if (mActive >= mMax) {
                        throw new RejectedExecutionException();
                    }
                    // Create a new thread if the number of active threads
                    // is less than the maximum allowed.
                    mActive++;
                    // Create outside synchronized block.
                }

                try {
                    thread = newPooledThread(command);
                    thread.start();
                } catch (Error e) {
                    mActive--;
                    throw e;
                }
                return;
            }

            if (thread.setCommand(command)) {
                return;
            }
            
            // Couldn't set the command because the pooled thread is exiting.
            // Wait for it to exit to ensure that the active count is less
            // than the maximum and try to obtain another thread.
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RejectedExecutionException(e);
            }
        }
    }

    public void shutdown() {
        synchronized (mPool) {
            mShutdown = true;
            for (PooledThread thread : mPool) {
                thread.setCommand(SHUTDOWN);
            }
            mPool.notifyAll();
        }
    }

    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }

    public boolean isShutdown() {
        synchronized (mPool) {
            return mShutdown;
        }
    }

    public boolean isTerminated() {
        synchronized (mPool) {
            return mShutdown && mActive <= 0;
        }
    }

    public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException {
        if (time < 0) {
            return false;
        }

        synchronized (mPool) {
            if (isTerminated()) {
                return true;
            }

            if (time == 0) {
                return false;
            }

            long start = System.currentTimeMillis();
            long millis = unit.toMillis(time);

            do {
                mPool.wait(millis);
                if ((millis -= System.currentTimeMillis() - start) <= 0) {
                    return isTerminated();
                }
            } while (!isTerminated());
        }

        return true;
    }

    void threadAvailable(PooledThread thread) {
        synchronized (mPool) {
            mPool.addLast(thread);
            mPool.notify();
        }
    }

    void threadExiting(PooledThread thread) {
        synchronized (mPool) {
            mPool.remove(thread);
            mActive--;
            mPool.notify();
        }
    }

    private PooledThread newPooledThread(Runnable command) {
        PooledThread thread = new PooledThread
            (mGroup, mNamePrefix + mThreadNumber.getAndIncrement(), command);

        if (thread.isDaemon() != mDaemon) {
            thread.setDaemon(mDaemon);
        }
        if (thread.getPriority() != Thread.NORM_PRIORITY) {
            thread.setPriority(Thread.NORM_PRIORITY);
        }
        if (mHandler != null) {
            thread.setUncaughtExceptionHandler(mHandler);
        }

        return thread;
    }

    private class PooledThread extends Thread {
        private Runnable mCommand;
        private boolean mExiting;

        public PooledThread(ThreadGroup group, String name, Runnable command) {
            super(group, null, name);
            mCommand = command;
        }

        synchronized boolean setCommand(Runnable command) {
            if (mCommand != null) {
                throw new IllegalStateException("Command in pooled thread is already set");
            }
            if (mExiting) {
                return false;
            } else {
                mCommand = command;
                notify();
                return true;
            }
        }

        private synchronized Runnable waitForCommand() {
            Runnable command;
            
            if ((command = mCommand) == null) {
                long idle = mIdleTimeout;
                
                if (idle != 0) {
                    try {
                        if (idle < 0) {
                            wait(0);
                        } else {
                            wait(idle);
                        }
                    } catch (InterruptedException e) {
                    }
                }
                    
                if ((command = mCommand) == null) {
                    mExiting = true;
                }
            }

            return command;
        }

        public void run() {
            try {
                while (!isShutdown()) {
                    if (Thread.interrupted()) {
                        continue;
                    }

                    Runnable command;

                    if ((command = waitForCommand()) == null) {
                        break;
                    }

                    try {
                        command.run();
                    } catch (ThreadDeath death) {
                        break;
                    } catch (Throwable e) {
                        getUncaughtExceptionHandler().uncaughtException(this, e);
                        e = null;
                    }

                    // Allow the garbage collector to reclaim command from
                    // stack while we wait for another command.
                    command = null;

                    mCommand = null;
                    threadAvailable(this);
                }
            } finally {
                threadExiting(this);
            }
        }
    }
}
