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

package org.cojen.dirmi;

import java.lang.annotation.*;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import java.rmi.RemoteException;

/**
 * Identify a method as being asynchronous, which does not imply
 * non-blocking. It merely means that the caller does not wait for the remote
 * method to finish. An asynchronous method will likely block if the transport
 * layer is backed up.
 *
 * <p>An asynchronous method must declare returning void, {@link Pipe}, or
 * {@link Future}. An asynchronous task represented by a {@code Future} cannot
 * be cancelled, at least not via the {@code Future} object. Implementations of
 * asynchronous future methods should return a factory generated {@link
 * Response response}.
 *
 * <pre>
 * <b>&#64;Asynchronous</b>
 * void sendMessage(String data) throws RemoteException;
 * </pre>
 *
 * <pre>
 * <b>&#64;Asynchronous</b>
 * &#64;RemoteFailure(exception=FileNotFoundException.class)
 * Pipe readFile(String name, Pipe pipe) throws FileNotFoundException;
 * </pre>
 *
 * <pre>
 * <b>&#64;Asynchronous</b>
 * Future&lt;Image&gt; generateImage(int width, int height, Object data) throws RemoteException;
 * </pre>
 *
 * <pre>
 * <b>&#64;Asynchronous(CallMode.ACKNOWLEDGED)</b>
 * void launchBuild(Object params, ProgressCallback callback) throws RemoteException;
 * </pre>
 *
 * Asynchronous methods which return void or a {@link Pipe} can only declare
 * throwing {@link RemoteException} or the exception indicated by {@link
 * RemoteFailure}. A client can expect an exception to be thrown by an
 * asynchronous method only if there is a communication failure. Any exception
 * thrown by the server implementation is not passed to the client, but it is
 * instead passed to the thread's uncaught exception handler.
 *
 * <p>Any exception thrown by an asynchronous method which returns a {@link
 * Future} is passed to the caller via the {@code Future}. Upon calling {@link
 * Future#get get}, an {@link ExecutionException} is thrown. A communication
 * failure while sending the request is thrown directly to the caller and not
 * through the {@code Future}.
 *
 * @author Brian S O'Neill
 * @see Batched
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Asynchronous {
    /**
     * Control the calling mode of the asynchronous method. By default, the
     * request is immediately sent to the remote endpoint, but it does not wait
     * for acknowledgement.
     */
    CallMode value() default CallMode.IMMEDIATE;
}
