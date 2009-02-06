/*
 *  Copyright 2009 Brian S O'Neill
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
 * Identify a remote method invocation as serialized. To be applicable, the
 * method must not have any arguments. The runtime value returned by the
 * serialized method must be {@code Serializable} or {@code Remote}.
 *
 * <p>Serialized methods provide a means to piggyback immutable data when
 * transporting a remote object. A serialized method is invoked when its
 * enclosing remote object is transported. Invocations on the remote endpoint
 * do not actually make a remote call, but they instead return the original
 * serialized value. Subsequent transports of the remote object over the same
 * session seldom invoke the serialized method again.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Serialized {
}
