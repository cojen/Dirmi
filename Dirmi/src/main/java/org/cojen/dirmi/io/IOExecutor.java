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

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.RejectedException;

/**
 * Executor which throws checked exceptions if no threads are available.
 *
 * @author Brian S O'Neill
 */
public class IOExecutor {
    private final ScheduledExecutorService mExecutor;

    public IOExecutor(ScheduledExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException();
        }
        mExecutor = executor;
    }

    public void execute(Runnable command) throws RejectedException {
        try {
            mExecutor.execute(command);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }

    public <T> Future<T> submit(Callable<T> task) throws RejectedException {
        try {
            return mExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }

    public <T> Future<T> submit(Runnable task, T result) throws RejectedException {
        try {
            return mExecutor.submit(task, result);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }

    public Future<?> submit(Runnable task) throws RejectedException {
        try {
            return mExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }

    public ScheduledFuture<?> schedule(Runnable command,
                                       long delay,
                                       TimeUnit unit)
        throws RejectedException
    {
        try {
            return mExecutor.schedule(command, delay, unit);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay,
                                           TimeUnit unit)
        throws RejectedException
    {
        try {
            return mExecutor.schedule(callable, delay, unit);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit)
        throws RejectedException
    {
        try {
            return mExecutor.scheduleAtFixedRate(command, initialDelay, period, unit);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit)
        throws RejectedException
    {
        try {
            return mExecutor.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        } catch (RejectedExecutionException e) {
            throw new RejectedException(mExecutor.isShutdown(), e);
        }
    }
}
