/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * Designates a remote method which returns a remote object which should be restored when a
 * session reconnects. All of the parameters passed to the method are referenced internally by
 * the returned remote object so that they can be used again. To ensure that the restoration
 * works idempotently, the contents of any object parameters must not be modified.
 *
 * <p>Objects which are {@link Disposer disposed} cannot be restored, and neither can objects
 * which depend on non-restorable objects. If the {@link Session#root root} object is disposed,
 * then the session will never attempt to reconnect, and thus no objects can be restored.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Restorable {
    /**
     * A restorable method which is lenient allows an object to be returned by a method even if
     * the session is currenly disconnected. Such an object will be in a disposed state and
     * won't be fully functional until the session reconnects.
     */
    boolean lenient() default false;
}
