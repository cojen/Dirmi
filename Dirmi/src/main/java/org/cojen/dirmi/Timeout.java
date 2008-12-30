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

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Remote method timeout policy annotation, which can target a remote interface
 * or method. When annotated at the interface level, it defines default timeout
 * values for all methods. Any method can override the defaults with its own
 * annotation. The timeout annotation can be used with {@link TimeoutUnit} to
 * define the unit. If not specified, it defaults to milliseconds.
 *
 * <p>Timeouts only apply to the caller of a remote method. If the timeout has
 * elapsed without a response from the remote endpoint, the connection is
 * forcibly closed and a {@link RemoteTimeoutException} is thrown. When this
 * happens, no special action is automatically applied on the remote side to
 * interrupt any work in progress. The actual method implementation is
 * responsible for this. If it completes its work and returns after a timeout,
 * the response is discarded.
 *
 * <p>When a {@link RemoteTimeoutException} is thrown, it does not indicate
 * whether the remote endpoint actually received the request or not. Care must
 * be taken when attempting to retry a failed remote invocation, especially if
 * not idempotent. {@link Asynchronous} methods with callbacks can provide more
 * information regarding successful invocation, but timeouts on asynchronous
 * methods alone offer less utility. This is because the timeout on an
 * asynchronous method only applies to the sending of the request. It does not
 * wait for method completion. When using the {@link CallMode#ACKNOWLEDGED
 * acknowledged} calling mode, timeouts do at least wait for acknowledgement.
 * Consider returning a {@link Future} and call the {@code get} method which
 * accepts a timeout.
 *
 * <p>Method parameters may use the {@link TimeoutParam} annotation to specify
 * timeout values and units. When applied to a primitive numeric type (which
 * can be boxed), it specifies the timeout value. At most one parameter can be
 * a timeout value. When the annotation is applied to a parameter of type
 * {@link TimeUnit}, it specifies the timeout unit. Likewise, at most one
 * parameter can be a timeout unit. If no parameter is annotated as a timeout
 * unit but the parameter immediately following the timeout value is a {@code
 * TimeUnit}, it is automatically chosen as the timeout unit parameter.
 *
 * <p>Remote method implementations may make use of timeout parameters to
 * interrupt any work in progress, under the assumption that the caller has
 * given up after the timeout has elapsed.
 *
 * <p>Timeout parameters can have runtime values of null, in which case default
 * timeouts apply. These defaults are defined by any method and interface level
 * timeout annotations. If none, then the default timeout value is infinite and
 * the unit is milliseconds. In either case, the remote endpoint sees the
 * applied values instead of null. If the timeout value cannot be cast to the
 * parameter type without loss of magnitude, -1 (infinite) is passed instead.
 *
 * <pre>
 * // 10 second timeout for all methods by default.
 * <b>&#64;Timeout(10)</b>
 * // If unit was not specified, milliseconds is assumed.
 * <b>&#64;TimeoutUnit(TimeUnit.SECONDS)</b>
 * public interface MyRemote extends Remote {
 *     // Default 10 second timeout applies.
 *     String getItemName(String id) throws RemoteException;
 *
 *     // Override with a 20 second timeout.
 *     <b>&#64;Timeout(20)</b>
 *     String getItemDescription(String id) throws RemoteException;
 *
 *     // Override with a 100 millisecond timeout.
 *     <b>&#64;Timeout(100)</b>
 *     <b>&#64;TimeoutUnit(TimeUnit.MILLISECONDS)</b>
 *     String disableItem(String id) throws RemoteException;
 *
 *     // A parameter is passed to define the timeout, overriding the default.
 *     // The timeout unit is seconds, as defined by the interface level annotation.
 *     void runReport(String param, <b>&#64;TimeoutParam</b> int timeout) throws RemoteException;
 *
 *     // A parameter and unit is passed to define the timeout. If runtime unit is
 *     // is null, it defaults to minutes.
 *     <b>&#64;TimeoutUnit(TimeUnit.MINUTES)</b>
 *     void runReport(String param, <b>&#64;TimeoutParam</b> int timeout, TimeUnit unit)
 *         throws RemoteException;
 *
 *     // The timeout parameter is explicitly annotated, because of its non-standard
 *     // argument position. If runtime unit is is null, it assumed to be seconds
 *     // because of interface level annotation.
 *     void runReport(String param, <b>&#64;TimeoutParam</b> TimeUnit unit, <b>&#64;TimeoutParam</b> int timeout)
 *         throws RemoteException;
 * }
 * </pre>
 *
 * @author Brian S O'Neill
 * @see TimeoutUnit
 * @see TimeoutParam
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Timeout {
    /**
     * Specify the timeout duration. Any negative value indicates an infinite
     * timeout.
     */
    long value();
}
