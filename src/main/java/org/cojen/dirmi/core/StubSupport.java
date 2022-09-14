/*
 *  Copyright 2022 Cojen.org
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
 * Object passed to a Stub instance in order for it to communicate with a remote object.
 *
 * @author Brian S O'Neill
 * @see StubFactory
 */
public interface StubSupport {
    /**
     * Called by unbatched methods to temporarily release a thread-local pipe.
     *
     * @return null if no batch is in progress
     */
    Pipe unbatch();

    /**
     * Called by unbatched methods to reinstate a thread-local pipe.
     *
     * @param pipe pipe returned by unbatch; can be null
     * @throws IllegalStateException if a thread-local pipe already exists
     */
    void rebatch(Pipe pipe);

    /**
     * Returns a new or existing connection. Caller chooses to flush the output after arguments
     * are written and then reads from the pipe.
     *
     * @return pipe for writing arguments and reading response
     */
    <T extends Throwable> Pipe connect(Class<T> remoteFailureException) throws T;

    /**
     * Used by batched methods which return a Remote object. This method writes an identifier
     * to the pipe, and returns a remote object of the requested type.
     *
     * @param type type of remote object returned by batched method
     * @return stub for remote object
     */
    <T extends Throwable, R> R createBatchedRemote(Class<T> remoteFailureException,
                                                   Pipe pipe, Class<R> type) throws T;

    /**
     * Called by synchronous methods after all parameters have been written and flushed. If the
     * remote endpoint threw an exception, then a non-null Throwable is returned.
     */
    Throwable readResponse(Pipe pipe) throws IOException;

    /**
     * Called after pipe usage is finished and can be reused for sending new requests. This
     * method should not throw any exception.
     */
    void finished(Pipe pipe);

    /**
     * Called after a batched request is sent over the pipe and the current thread should hold
     * the pipe. This method should not throw any exception.
     */
    void batched(Pipe pipe);

    /**
     * Called if invocation failed due to a problem with the pipe, and it should be
     * closed. This method should not throw any exception, but it must return an appropriate
     * Throwable which will get thrown to the client.
     */
    <T extends Throwable> T failed(Class<T> remoteFailureException, Pipe pipe, Throwable cause);

    /**
     * Disposes the given stub and returns a StubSupport instance which throws
     * NoSuchObjectException for all of the above methods.
     */
    StubSupport dispose(Stub stub);
}
