/*
 *  Copyright 2006-2022 Cojen.org
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

import java.io.IOException;

import org.cojen.dirmi.Pipe;

/**
 * A Skeleton instance wraps a server-side Remote object, unmarshals client requests, and
 * invokes server-side methods. Any response is marshaled back to the client.
 *
 * @author Brian S O'Neill
 * @see SkeletonFactory
 */
public abstract class Skeleton<R> extends Item {
    public static final Object STOP_READING = new Object();

    private static final Object BATCHING = new Object();

    public Skeleton(long id) {
        super(id);
    }

    public abstract Class<R> type();

    public abstract long typeId();

    /**
     * Returns the Remote object managed by this Skeleton, which is null for a broken skeleton.
     */
    public abstract R server();

    /**
     * Invoke a remote method on the server. Any exception thrown from the invoked method is
     * written to the pipe, unless the method is asynchronous. If an exception is thrown from
     * this method, then the pipe must be closed. If the exception type is UncaughtException,
     * then it should be logged. Any type of IOException should be treated as a communication
     * failure and need not be logged.
     *
     * @param pipe pipe for reading method arguments and for writing any response
     * @return the original or modified context; caller should stop reading if is STOP_READING
     */
    public abstract Object invoke(Pipe pipe, Object context) throws Throwable;

    /**
     * Called when an exception is thrown when invoking a remote method. The original exception
     * is returned or else a suitable replacement is returned if this is a broken skeleton.
     */
    public Throwable checkException(Throwable exception) {
        return exception;
    }

    public static void batchedResultCheck(Object server, String methodName, Object result) {
        if (result == null) {
            nullBatchedResult(server, methodName);
        }
    }

    private static void nullBatchedResult(Object server, String methodName) {
        String message = "Cannot return null from a batched method: " +
            server.getClass().getName() + '.' + methodName;
        throw new IllegalStateException(message);
    }

    /**
     * @return null if no exception is present
     */
    public static Throwable batchException(Object context) {
        return context instanceof Throwable ? (Throwable) context : null;
    }

    /**
     * @return the original or modified context
     */
    public static Object batchInvokeSuccess(Object context) {
        return context == null ? BATCHING : context;
    }

    /**
     * @return the original or modified context
     */
    public static Object batchInvokeFailure(Pipe pipe, Object context, Throwable exception) {
        // If the batch contains remote objects which must be disposed of, then reason is
        // written as the exception object itself. Enable reference tracking to avoid writing
        // the same exception instance out multiple times. It's disabled when batchFinish is
        // called, which writes the exception to the caller which caused the exception.
        pipe.enableReferences();
        return exception;
    }

    /**
     * If in a batch, writes the batch responses.
     *
     * @return -1 if no batch, 0 if the batch finished normally, or 1 if the batch sequence
     * finished with an exception
     */
    public static int batchFinish(Pipe pipe, Object context) throws IOException {
        if (context == null) {
            return -1;
        } else if (context instanceof Throwable) {
            pipe.writeObject(context);
            pipe.disableReferences();
            return 1;
        } else {
            pipe.writeNull();
            return 0;
        }
    }
}
