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

package dirmi;

import java.lang.annotation.*;

/**
 * Specify the maximum time to wait for a remote method to respond. If it fails
 * to responsd in time, a {@link ResponseTimeoutException} is thrown and the
 * thread running the remote operation is interrupted, in an attempt to stop
 * it. A method that lacks a ResponseTimeout annotation potentially waits
 * forever to respond.
 *
 * <p>The timeout value may be specified in minutes, seconds, milliseconds, or
 * in any combination of these fields. The fields are summed up into a single
 * timeout value. If no fields are specified, the timeout defaults to 0, which
 * means a request is sent, but a ResponseTimeoutException is thrown everytime.
 *
 * <p>A ResponseTimeout can be applied to a Remote interface as well, which in
 * turn applies to all of its declared methods. A method can override this by
 * declaring a ResponseTimeout annotation of its own.
 *
 * <p>Note: The response timer does not begin until the request has been fully
 * written. ResponseTimeout is not intended as a general purpose mechanism for
 * detecting network failure.
 *
 * @author Brian S O'Neill
 * @see Asynchronous
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ResponseTimeout {
    /**
     * Specify timeout minutes field.
     */
    int minutes() default 0;

    /**
     * Specify timeout seconds field.
     */
    int seconds() default 0;

    /**
     * Specify timeout milliseconds field.
     */
    int millis() default 0;
}
