/*
 *  Copyright 2006 Brian S O'Neill
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

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Object passed to a Stub instance in order for it to actually communicate
 * with a remote object.
 *
 * @author Brian S O'Neill
 * @see StubFactory
 */
public interface StubSupport {
    /**
     * Returns an InvocationChannel which has nothing written to it yet.
     */
    <T extends Throwable> InvocationChannel prepare(Class<T> remoteFailureException) throws T;

    /**
     * Writes request header to prepared channel. Caller chooses to flush
     * output after arguments are written and then reads channel.
     *
     * @throws java.rmi.NoSuchObjectException if support has been disposed
     */
    <T extends Throwable> void invoke(Class<T> remoteFailureException,
                                      InvocationChannel channel) throws T;

    /**
     * Writes request header to prepared channel. Caller chooses to flush
     * output after arguments are written and then reads channel.
     *
     * @throws java.rmi.NoSuchObjectException if support has been disposed
     * @return task to close channel after provided timeout has elapsed
     */
    <T extends Throwable> Future<?> invoke(Class<T> remoteFailureException,
                                           InvocationChannel channel,
                                           long timeout, TimeUnit unit) throws T;

    /**
     * Writes request header to prepared channel. Caller chooses to flush
     * output after arguments are written and then reads channel.
     *
     * @throws java.rmi.NoSuchObjectException if support has been disposed
     * @return task to close channel after provided timeout has elapsed
     */
    <T extends Throwable> Future<?> invoke(Class<T> remoteFailureException,
                                           InvocationChannel channel,
                                           double timeout, TimeUnit unit) throws T;
    /**
     * Used by batched methods which return a Remote object. This method writes
     * an identifier to the channel, and returns a remote object of the
     * requested type.
     *
     * @param type type of remote object returned by batched method
     * @return stub for remote object
     */
    <T extends Throwable, R extends Remote> R createBatchedRemote(Class<T> remoteFailureException,
                                                                  InvocationChannel channel,
                                                                  Class<R> type) throws T;

    /**
     * Called after batched request is sent over channel and current thread
     * should hold channel. This method should not throw any exception.
     */
    void batched(InvocationChannel channel);

    /**
     * Called after batched request is sent over channel and current thread
     * should hold channel. This method should not throw any exception.
     *
     * @param closeTask optional close task for timed requests
     */
    void batched(InvocationChannel channel, Future<?> closeTask);

    /**
     * Called after channel usage is finished and can be reused for sending
     * new requests. This method should not throw any exception.
     */
    void finished(InvocationChannel channel);

    /**
     * Called after channel usage is finished and can be reused for sending
     * new requests. This method should not throw any exception.
     *
     * @param closeTask optional close task for timed requests
     */
    void finished(InvocationChannel channel, Future<?> closeTask);

    /**
     * Called if invocation failed due to a problem with the channel, and it
     * should be closed. This method should not throw any exception, however it
     * must return an appropriate Throwable which will get thrown to the client.
     */
    <T extends Throwable> T failed(Class<T> remoteFailureException,
                                   InvocationChannel channel,
                                   Throwable cause);

    /**
     * Called if invocation failed due to a problem with the channel, and it
     * should be closed. This method should not throw any exception, however it
     * must return an appropriate Throwable which will get thrown to the client.
     *
     * @param closeTask close task for timed requests
     */
    <T extends Throwable> T failed(Class<T> remoteFailureException,
                                   InvocationChannel channel,
                                   Throwable cause,
                                   long timeout, TimeUnit unit, Future<?> closeTask);

    /**
     * Called if invocation failed due to a problem with the channel, and it
     * should be closed. This method should not throw any exception, however it
     * must return an appropriate Throwable which will get thrown to the client.
     *
     * @param closeTask close task for timed requests
     */
    <T extends Throwable> T failed(Class<T> remoteFailureException,
                                   InvocationChannel channel,
                                   Throwable cause,
                                   double timeout, TimeUnit unit, Future<?> closeTask);

    /**
     * Returns a hashCode implementation for the Stub.
     */
    int stubHashCode();

    /**
     * Returns a partial equals implementation for the Stub.
     */
    boolean stubEquals(StubSupport support);

    /**
     * Returns a partial toString implementation for the Stub.
     */
    String stubToString();
}
