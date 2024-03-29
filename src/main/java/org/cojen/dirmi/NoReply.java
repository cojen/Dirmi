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
 * Designates a remote method which doesn't block reading a reply or acknowledgment. The method
 * can only return void, and any exception thrown from the implementation is treated as {@link
 * Environment#uncaughtExceptionHandler uncaught}.
 *
 * <p>A {@code NoReply} method implementation is run in the same thread that processes incoming
 * requests for a particular socket. Therefore, the implementation should finish quickly or
 * else continue execution in another thread.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface NoReply {
}
