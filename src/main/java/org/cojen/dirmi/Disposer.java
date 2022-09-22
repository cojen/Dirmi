/*
 *  Copyright 2009-2022 Cojen.org
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
 * Identify a method as being a {@link Remote} object disposer, allowing memory to be freed on
 * the remote side.
 *
 * <p>When a disposer method is called, it immediately prevents all new remote method
 * invocations on the same object from functioning. Any attempt causes an exception to be
 * thrown. The actual disposer call is allowed to complete normally, but any other methods
 * running concurrently might fail.
 *
 * {@snippet lang="java" :
 * @Disposer
 * void close() throws RemoteException;
 * }
 *
 * @author Brian S O'Neill
 * @see SessionAware
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Disposer {
}
