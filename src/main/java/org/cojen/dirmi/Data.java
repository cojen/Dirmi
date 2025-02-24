/*
 *  Copyright 2025 Cojen.org
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
 * Designates a remote method which is just data, which is serialized whenever the remote
 * object is transported over the session. Data methods cannot have any parameters, and they
 * cannot have any other annotations except {@link Serialized @Serialized}.
 *
 * <p>If a remote object is returned by a {@link Batched @Batched} method, then all of its data
 * methods will throw {@link DataUnavailableException}. This limitation might be fixed in a
 * future version.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Data {
}
