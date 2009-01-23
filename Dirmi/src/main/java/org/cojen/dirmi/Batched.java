/*
 *  Copyright 2008 Brian S O'Neill
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

package org.cojen.dirmi;

import java.lang.annotation.*;

import java.lang.reflect.UndeclaredThrowableException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import java.rmi.Remote;

/**
 * Identify a method as being batched and asynchronous, which does not imply
 * non-blocking. Requests are sent to the remote endpoint, but the channel
 * is not immediately flushed. The current thread holds the same channel for
 * making additional requests until an immediate or synchronous request is
 * sent. If the current thread exits before releasing the channel, the batched
 * request is eventually sent.
 *
 * <p>A batched method must declare returning void, a {@link Remote} object,
 * {@link Completion} or {@link Future}. Returning a {@code Remote} object
 * allows batched calls to be chained together. A batched task represented by a
 * Completion or Future cannot be cancelled, at least not
 * directly. Implementations of batched future methods should return a factory
 * generated {@link Response response}.
 *
 * <pre>
 * <b>&#64;Batched</b>
 * void setOption(int option) throws RemoteException;
 *
 * <b>&#64;Batched</b>
 * RemoteAccess login(String user, String password) throws RemoteException, AuthFailure;
 * </pre>
 *
 * Batched methods can declare throwing any exception, and any exception thrown
 * by the server aborts the batch operation. This exception is passed to the
 * caller of the immediate or synchronous method that terminated the batch. If
 * the terminating method does not declare throwing the exception type, it is
 * wrapped by {@link UndeclaredThrowableException}. Any {@code Remote} objects
 * returned from batched methods at or after the exception being thrown will be
 * bogus. Attempts to invoke methods on these objects will also throw the
 * original exception, possibly wrapped.
 *
 * <p>Any exception thrown by a batched method which returns a {@link
 * Completion} or {@link Future} is passed to the caller via the returned
 * object. Upon calling {@link Future#get get}, an {@link ExecutionException}
 * is thrown. A communication failure while sending the request is thrown
 * directly to the caller and not through the {@code Future}.
 *
 * @author Brian S O'Neill
 * @see Asynchronous
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Batched {
}
