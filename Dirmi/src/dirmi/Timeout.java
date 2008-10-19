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

package dirmi;

import java.lang.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * Remote method timeout policy annotation, which can target a remote
 * interface, method or parameter. When annotated at the interface level, it
 * defines default timeout values for all methods. Any method can override the
 * defaults with its own annotation.
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
 * it isn't idempotent. {@link Asynchronous} methods with callbacks can provide
 * more information regarding successful invocation, but timeouts on
 * asynchronous methods alone provide less information. This is because the
 * timeout on an asynchronous method only applies to the sending of the
 * request, not its acknowledgment.
 *
 * <p>A method may use a parameter timeout annotation to allow runtime values
 * to determine the effective timeout. This annotation can be applied to a
 * primitive numeric type, which may also be boxed. At most one parameter can
 * be annotated as the timeout value. Remote method implementations may make
 * use of timeout parameters to interrupt any work in progress, under the
 * assumption that the caller has given up after the timeout has elapsed.
 *
 * <p>For an annotated timeout value parameter, if the next immediate parameter
 * is a {@link TimeUnit}, it is interpreted to be the associated timeout
 * unit. If the position of the timeout unit parameter is different, another
 * parameter may be explicitly annotated as the {@link TimeoutUnit timeout
 * unit}. This parameter type must be a {@link TimeUnit}.
 *
 * <p>Timeout parameters can have runtime values of null, in which case default
 * timeouts apply. These defaults are defined by any method and interface level
 * timeout annotations. If none, then the default timeout value is infinite and
 * the unit is milliseconds.
 *
 * @author Brian S O'Neill
 * @see TimeoutUnit
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface Timeout {
    /**
     * Specify the timeout duration, which defaults to infinite. Any negative
     * value indicates an infinite timeout.
     */
    long value() default -1;
}
