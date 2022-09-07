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

package org.cojen.dirmi.core;

/**
 * Wraps an exception caught when invoking a batched method, to be written to the client after
 * all input has been drained.
 *
 * @author Brian S O'Neill
 * @see BatchedContext
 */
public final class BatchedException extends Exception {
    static BatchedException make(Throwable ex) {
        if (ex instanceof BatchedException) {
            return (BatchedException) ex;
        }
        return new BatchedException(ex);
    }

    private BatchedException(Throwable cause) {
        super(cause);
    }

    /**
     * The exception shouldn't propagate to the caller so no need to capture the trace.
     */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
