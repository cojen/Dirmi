/*
 *  Copyright 2008-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.Future;

/**
 * Object passed to a Skeleton instance in order for it to decide when channels
 * can be reused.
 *
 * @author Brian S O'Neill
 */
public interface SkeletonSupport {
    /**
     * Used by asynchronous methods which return a Future to write completion
     * response.
     */
    <V> void completion(Future<V> response, RemoteCompletion<V> completion) throws RemoteException;

    /**
     * Used by batched methods which return a Remote object. This method
     * creates a skeleton for the given remote object and registers it.
     *
     * @param skeleton skeleton which was invoked
     * @param skeletonMethodName name skeleton method invoked
     * @param typeId type ID assigned by client
     * @param remoteId object ID assigned by client
     * @param type type of remote object
     * @param remote remote object returned by server method
     */
    <R extends Remote> void linkBatchedRemote(Skeleton skeleton,
                                              String skeletonMethodName,
                                              Identifier typeId, VersionedIdentifier remoteId,
                                              Class<R> type, R remote)
        throws RemoteException;

    /**
     * Used by batched methods which return a Remote object. This method is
     * called if server method throws an exception.
     *
     * @param type type of remote object
     * @param cause thrown by server method
     * @return instance of remote object which throws the cause from every method
     */
    <R extends Remote> R failedBatchedRemote(Class<R> type, Throwable cause);

    /**
     * Called after synchronous method is finished with channel. This method
     * should not throw any exception.
     *
     * @param reset pass true if object output should be reset
     * @return true if caller should read another request from channel
     */
    boolean finished(InvocationChannel channel, boolean reset);

    /**
     * Called after synchronous method threw an exception and is finished with
     * channel. Exception is written to channel and output is reset. This
     * method should not throw any exception.
     *
     * @return true if caller should read another request from channel
     */
    boolean finished(InvocationChannel channel, Throwable cause);

    /**
     * Called after asynchronous method is finished with channel. This method
     * should not throw any exception.
     */
    void finishedAsync(InvocationChannel channel);

    /**
     * Called if an asynchronous method throws an exception which cannot be
     * passed to the remote caller.
     */
    void uncaughtException(Throwable cause);

    /**
     * Called if Skeleton needs to support ordered method invocation.
     */
    OrderedInvoker createOrderedInvoker();

    /**
     * Called by a disposer method when finished executing.
     */
    void dispose(VersionedIdentifier objId);
}
