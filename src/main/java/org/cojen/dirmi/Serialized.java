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
 * Indicates that a remote method should use Java object serialization for the parameters and
 * return value. Object serialization isn't very efficient, and so built-in and {@link
 * Environment#customSerializers custom} serialization is generally preferred.
 *
 * <p>This feature isn't supported for {@link Pipe piped} methods, and instead Java object
 * serialization must be applied manually. Care must be taken when recycling the pipe to ensure
 * that the object input stream doesn't read too far ahead.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Serialized {
    /**
     * Specify which classes should be rejected or allowed by serialization. Undecided classes
     * are rejected.
     *
     * @see java.io.ObjectInputFilter ObjectInputFilter
     */
    String filter();
}
