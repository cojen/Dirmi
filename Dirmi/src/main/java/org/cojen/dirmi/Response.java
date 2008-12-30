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

package org.cojen.dirmi;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Generates responses for an {@link Asynchronous} method which returns a
 * {@link Future}.
 *
 * @author Brian S O'Neill
 */
public class Response {
    private Response() {
    }

    /**
     * Returns a {@code Future} which always returns the given value.
     */
    public static <V> Future<V> complete(final V value) {
        return new Future<V>() {
            public boolean cancel(boolean mayInterrupt) {
                return false;
            }
            public boolean isCancelled() {
                return false;
            }
            public boolean isDone() {
                return true;
            }
            public V get() {
                return value;
            }
            public V get(long timeout, TimeUnit init) {
                return value;
            }
        };
    }

    /**
     * Returns a {@code Future} which always throws an {@link
     * ExecutionException} wrapping the given cause.
     */
    public static <V> Future<V> exception(final Throwable cause) {
        return new Future<V>() {
            public boolean cancel(boolean mayInterrupt) {
                return false;
            }
            public boolean isCancelled() {
                return false;
            }
            public boolean isDone() {
                return true;
            }
            public V get() throws ExecutionException {
                throw new ExecutionException(cause);
            }
            public V get(long timeout, TimeUnit init) throws ExecutionException {
                throw new ExecutionException(cause);
            }
        };
    }
}
