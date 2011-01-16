/*
 *  Copyright 2006-2011 Brian S O'Neill
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

/**
 * Method annotation which indicates that it can be traced. In order to enable tracing,
 * the {@link org.cojen.dirmi.trace.TraceAgent TraceAgent} must be installed. Method
 * tracing is not limited to remote methods. Any method can be traced, even if no remote
 * features of Dirmi are being used.
 *
 * <p>When using this annotation on remote interfaces, tracing is applied only to
 * client-side invocations. For server-side tracing, the implementing class must provide
 * its own trace annotations.
 *
 * <p>Because method tracing increases execution overhead, use the feature
 * judiciously. Ideal candidates are those which perform complex calculations or depend on
 * external resources.
 *
 * @author Brian S O'Neill
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Trace {
    // Note: Any changes to the defaults must also be made to Transformer since
    // this annotation is not runtime visible.

    /**
     * Optionally specify an operation name to pass to trace handler.
     */
    String operation() default "";

    /**
     * Set to true to pass method arguments to trace handler.
     */
    boolean args() default false;

    /**
     * Set to true to pass method return value to trace handler.
     */
    boolean result() default false;

    /**
     * Set to true to pass thrown exception to trace handler.
     */
    boolean exception() default false;

    /**
     * Set to false to disable method execution time measuring.
     */
    boolean time() default true;

    /**
     * Set to true to indicate to trace handlers that this trace should be
     * reported as a call entry root, even if the current thread is already
     * being traced.
     */
    boolean root() default false;

    /**
     * Set to true to indicate to trace handlers that this trace should be
     * ignored unless the current thread is already being traced. In general,
     * set graft for methods which are expected to run quickly.
     */
    boolean graft() default false;
}
