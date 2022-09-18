/*
 *  Copyright 2008-2022 Cojen.org
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

import java.lang.reflect.UndeclaredThrowableException;

/**
 * Identify a method as being batched, which can be used to reduce transport overhead. Method
 * calls are sent to the remote endpoint, but the pipe isn't immediately flushed. The calling
 * thread holds the same pipe for making additional method calls until a non-batched method is
 * called. All method calls received by the remote endpoint are executed in one thread, in the
 * original order.
 *
 * <p>A batched method must declare returning {@code void} or a {@link Remote}
 * object. Returning a remote object allows batched calls to be chained together.
 *
 * <p>Batched methods can declare throwing any exception, and any exception thrown by the
 * remote endpoint aborts the batch operation. The exception is thrown to the caller of the
 * method that flushed the batch. If this method does not declare throwing the proper exception
 * type, it's wrapped by {@link UndeclaredThrowableException}. Any {@code Remote} objects
 * returned from batched method calls at or after the exception point will be bogus. Attempts
 * to invoke methods on these objects will also throw the original exception, possibly wrapped.
 *
 * @author Brian S O'Neill
 * @see Unbatched
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Batched {
}
