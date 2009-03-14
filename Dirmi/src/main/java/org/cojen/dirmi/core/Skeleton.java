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

package org.cojen.dirmi.core;

import java.io.IOException;

import java.rmi.Remote;

import java.rmi.server.Unreferenced;

import org.cojen.dirmi.NoSuchObjectException;

/**
 * A Skeleton instance wraps a server-side Remote object, unmarshalls client
 * requests, and invokes server-side methods. Any response is marshalled back
 * to the client.
 *
 * @author Brian S O'Neill
 * @see SkeletonFactory
 */
public interface Skeleton<R extends Remote> extends Unreferenced {
    /**
     * Returns the Remote object managed by this Skeleton.
     */
    R getRemoteServer();

    /**
     * Invoke a non-serialized method in server-side instance. Any exception
     * thrown from the invoked method is written to the channel, unless method
     * is asynchronous or batched. Any other exception thrown from this method
     * indicates a communication failure, and so the channel should be closed.
     *
     * <p>If this invocation is after a batched call which threw an exception,
     * the batchedException parameter wraps it. If non-null, the input
     * arguments must be discarded and the method not actually invoked. Next,
     * if method is batched, the same exception is re-thrown. Otherwise, the
     * exception is converted to a type compatible with the method's throwable
     * exception types and handled like any other thrown exception. For
     * synchronous methods, this means the exception is written to the channel.
     *
     * @param methodId method to invoke
     * @param channel InvocationChannel for reading method arguments and for
     * writing response.
     * @param batchedException optional exception which was thrown earlier in a batch request
     * @return true if caller should read another request from channel
     * @throws IOException if thrown from channel
     * @throws NoSuchMethodException if method is unknown
     * @throws NoSuchObjectException if remote parameter refers to an unknown object
     * @throws ClassNotFoundException if unmarshalling an object parameter
     * refers to an unknown class
     * @throws AsynchronousInvocationException if method is asynchronous and
     * throws an exception
     * @throws BatchedInvocationException if method is batched and
     * throws an exception
     */
    boolean invoke(int methodId, InvocationChannel channel,
                   BatchedInvocationException batchedException)
        throws IOException,
               NoSuchMethodException,
               NoSuchObjectException,
               ClassNotFoundException,
               AsynchronousInvocationException,
               BatchedInvocationException;
}
