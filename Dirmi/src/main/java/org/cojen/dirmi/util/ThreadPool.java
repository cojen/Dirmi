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

package org.cojen.dirmi.util;

import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.TreeSet;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom thread pool implementation which is slightly more efficient than the
 * default JDK1.6 thread pool and also provides scheduling services.
 *
 * @author Brian S O'Neill
 */
public class ThreadPool extends AbstractExecutorService implements ScheduledExecutorService {
    static final Runnable SHUTDOWN = new Runnable() {
        public void run() {
            throw new ThreadDeath();
        }
    };

    private static final AtomicLong cPoolNumber = new AtomicLong(1);
    static final AtomicLong cTaskNumber = new AtomicLong(1);

    private final ThreadGroup mGroup;
    private final AtomicLong mThreadNumber = new AtomicLong(1);
    private final String mNamePrefix;
    private final boolean mDaemon;
    private final Thread.UncaughtExceptionHandler mHandler;

    private final int mMax;
    private final long mIdleTimeout = 60000;

    // Pool is accessed like a stack.
    private final LinkedList<PooledThread> mPool;

    private final TreeSet<Task> mScheduledTasks;
    private TaskRunner mTaskRunner;

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

        mScheduledTasks = new TreeSet<Task>();
    }

    public void execute(Runnable command) throws RejectedExecutionException {
        if (command == null) {
            throw new NullPointerException("Command is null");
        }

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
                        throw new RejectedExecutionException("Too many active threads");
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

    public ScheduledFuture<?> schedule(final Runnable command, long delay, TimeUnit unit) {
        return new Task<Object>(Executors.callable(command), delay, 0, unit);
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return new Task<V>(callable, delay, 0, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit)
    {
        if (period <= 0) {
            throw new IllegalArgumentException();
        }
        return new Task<Object>(Executors.callable(command), initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit)
    {
        if (delay <= 0) {
            throw new IllegalArgumentException();
        }
        return new Task<Object>(Executors.callable(command), initialDelay, -delay, unit);
    }

    public void shutdown() {
        synchronized (mPool) {
            mShutdown = true;
            for (PooledThread thread : mPool) {
                thread.setCommand(SHUTDOWN);
            }
            mPool.notifyAll();
        }
        synchronized (mScheduledTasks) {
            mScheduledTasks.clear();
            mScheduledTasks.notifyAll();
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

    void scheduleTask(Task<?> task) {
        if (isShutdown()) {
            throw new RejectedExecutionException("Thread pool is shutdown");
        }
        synchronized (mScheduledTasks) {
            if (!mScheduledTasks.add(task)) {
                throw new InternalError();
            }
            if (mScheduledTasks.first() == task) {
                if (mTaskRunner == null) {
                    mTaskRunner = new TaskRunner();
                    execute(mTaskRunner);
                } else {
                    mScheduledTasks.notify();
                }
            }
        }
    }

    void removeTask(Task<?> task) {
        synchronized (mScheduledTasks) {
            mScheduledTasks.remove(task);
            if (mScheduledTasks.size() == 0) {
                mScheduledTasks.notifyAll();
            }
        }
    }

    // Returns null if no more tasks
    Task<?> acquireTask(boolean canWait) {
        synchronized (mScheduledTasks) {
            while (true) {
                if (mScheduledTasks.size() == 0) {
                    return null;
                }
                Task<?> task = mScheduledTasks.first();
                long now = System.currentTimeMillis();
                long at = task.getAtMillis();
                if (at <= now) {
                    mScheduledTasks.remove(task);
                    if (mScheduledTasks.size() == 0) {
                        mScheduledTasks.notifyAll();
                    }
                    return task;
                }
                if (canWait) {
                    try {
                        mScheduledTasks.wait(at - now);
                    } catch (InterruptedException e) {
                    }
                } else {
                    return null;
                }
            }
        }
    }

    void tryReplace(TaskRunner runner) {
        synchronized (mScheduledTasks) {
            if (mScheduledTasks.size() > 0 && mTaskRunner == runner) {
                runner = new TaskRunner();
                try {
                    execute(runner);
                    mTaskRunner = runner;
                } catch (RejectedExecutionException e) {
                }
            }
        }
    }

    boolean canExit(TaskRunner runner) {
        if (!isShutdown()) {
            synchronized (mScheduledTasks) {
                if (mTaskRunner == runner) {
                    mTaskRunner = null;
                }
                if (mTaskRunner == null && mScheduledTasks.size() != 0) {
                    mTaskRunner = runner;
                    return false;
                }
            }
        }
        return true;
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

    private class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {
        private final long mNum;
        private final long mPeriodMillis;

        private volatile long mAtMillis;

        /**
         * @param period Period in milliseconds for repeating tasks. A positive
         * value indicates fixed-rate execution. A negative value indicates
         * fixed-delay execution. A value of 0 indicates a non-repeating task.
         */
        Task(Callable<V> callable, long initialDelay, long period, TimeUnit unit) {
            super(callable);

            long periodMillis;
            if (period == 0) {
                periodMillis = 0;
            } else if ((periodMillis = unit.toMillis(period)) == 0) {
                // Account for any rounding error.
                periodMillis = period < 0 ? -1 : 1;
            }
            mPeriodMillis = periodMillis;

            mNum = cTaskNumber.getAndIncrement();

            mAtMillis = System.currentTimeMillis();
            if (initialDelay > 0) {
                mAtMillis += unit.toMillis(initialDelay);
            }

            scheduleTask(this);
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(mAtMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        public int compareTo(Delayed delayed) {
            if (this == delayed) {
                return 0;
            }
            if (delayed instanceof Task) {
                Task<?> other = (Task<?>) delayed;
                long diff = mAtMillis - other.mAtMillis;
                if (diff < 0) {
                    return -1;
                } else if (diff > 0) {
                    return 1;
                } else if (mNum < other.mNum) {
                    return -1;
                } else {
                    return 1;
                }
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - delayed.getDelay(TimeUnit.NANOSECONDS);
            return diff == 0 ? 0 : (diff < 0 ? -1 : 1);
        }

        @Override
        public void run() {
            long periodMillis = mPeriodMillis;
            if (periodMillis == 0) {
                super.run();
            } else if (super.runAndReset()) {
                if (periodMillis > 0) {
                    mAtMillis += periodMillis;
                } else {
                    mAtMillis = System.currentTimeMillis() - periodMillis;
                }
                try {
                    scheduleTask(this);
                } catch (RejectedExecutionException e) {
                }
            }
        }

        @Override
        protected void done() {
            removeTask(this);
        }

        long getAtMillis() {
            return mAtMillis;
        }
    }

    private class TaskRunner implements Runnable {
        TaskRunner() {
        }

        public void run() {
            do {
                try {
                    Task<?> task = acquireTask(true);
                    if (task != null) {
                        // Try to replace this runner, to allow task to block.
                        tryReplace(this);

                        task.run();

                        // Run any more tasks which need to be run immediately, to
                        // avoid having to switch context.
                        while ((task = acquireTask(false)) != null) {
                            task.run();
                        }

                        // Allow the garbage collector to reclaim task from stack
                        // if runner cannot exit.
                        task = null;
                    }
                } catch (Throwable e) {
                    Thread t = Thread.currentThread();
                    t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    e = null;
                }
            } while (!canExit(this));
        }
    }
}
