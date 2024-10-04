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
import org.cojen.dirmi.Session;

/**
 * Object passed to a StubInvoker instance in order for it to communicate with a remote object.
 *
 * @author Brian S O'Neill
 * @see StubFactory
 */
public interface StubSupport {
    Session session();

    public default boolean isLenientRestorable() {
        return false;
    }

    default void appendInfo(StringBuilder b) {
        Session session = session();
        b.append(", localAddress=").append(session.localAddress())
            .append(", remoteAddress=").append(session.remoteAddress());
    }

    /**
     * Returns a new or existing connection. Caller chooses to flush the output after arguments
     * are written and then reads from the pipe.
     *
     * @param stub the stub requesting a connection
     * @return pipe for writing arguments and reading response
     */
    <T extends Throwable> Pipe connect(StubInvoker stub, Class<T> remoteFailureException) throws T;

    /**
     * Variant which never returns the thread-local pipe used by batched calls.
     *
     * @param stub the stub requesting a connection
     * @return pipe for writing arguments and reading response
     */
    <T extends Throwable> Pipe connectUnbatched(StubInvoker stub, Class<T> remoteFailureException)
        throws T;

    /**
     * Variant which returns null if a connection cannot be established.
     *
     * @param stub the stub requesting a connection
     * @return pipe for writing arguments and reading response
     */
    <T extends Throwable> Pipe tryConnect(StubInvoker stub, Class<T> remoteFailureException)
        throws T;

    /**
     * Variant which returns null if a connection cannot be established.
     *
     * @param stub the stub requesting a connection
     * @return pipe for writing arguments and reading response
     */
    <T extends Throwable> Pipe tryConnectUnbatched(StubInvoker stub,
                                                   Class<T> remoteFailureException)
        throws T;

    /**
     * Checks if the stub support instance is the correct after connecting. If so, true is
     * returned. Otherwise, the caller should obtain the support instance again, and then call
     * connect again.
     */
    boolean validate(StubInvoker stub, Pipe pipe);

    /**
     * Used by batched methods which return a Remote object. If the remote typeId is currently
     * unknown, then zero is returned.
     *
     * @param type type of remote object returned by batched method
     */
    long remoteTypeId(Class<?> type);

    /**
     * Used by batched methods which return a Remote object.
     */
    default long newAliasId() {
        return IdGenerator.nextNegative();
    }

    /**
     * Used by batched methods which return a Remote object.
     *
     * @param aliasId alias identifier as returned by the newAliasId method
     * @param typeId non-zero typeId as provided by the remoteTypeId method
     * @return stub for remote object
     */
    <T extends Throwable> Object newAliasStub(Class<T> remoteFailureException,
                                              long aliasId, long typeId) throws T;

    /**
     * Returns a stub which doesn't actually work. Used by lenient restorable methods when the
     * connect attempt fails.
     *
     * @param cause optional
     */
    StubInvoker newDisconnectedStub(Class<?> type, Throwable cause);

    /**
     * Returns true if a batch sequence is in progress.
     */
    boolean isBatching(Pipe pipe);

    /**
     * Called by non-batched methods after being invoked. If true is returned, then
     * readResponse must be called to detect if there was any exception from the batch
     * sequence. If so, then it should be thrown instead of reading the response from the
     * non-batched method.
     */
    boolean finishBatch(Pipe pipe);

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
     * Disposes the given stub.
     */
    void dispose(StubInvoker stub);
}
