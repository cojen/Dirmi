/*
 *  Copyright 2008-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

/**
 * If an exception is thrown from a skeleton-invoked method which is batched,
 * it must be thrown from the first non-batched method encountered in the
 * batch stream.
 *
 * @author Brian S O'Neill
 */
public final class BatchedInvocationException extends Exception {
    private static final long serialVersionUID = 1;

    public static BatchedInvocationException make(Throwable cause) {
        if (cause instanceof BatchedInvocationException) {
            return (BatchedInvocationException) cause;
        }
        return new BatchedInvocationException(cause);
    }

    private BatchedInvocationException(Throwable cause) {
        super(cause);
    }

    /**
     * Returns true if cause of this exception matches the declared type or is
     * unchecked.
     */
    public boolean isCauseDeclared(Class declaredType) {
        Throwable cause = getCause();
        return (cause instanceof RuntimeException) || (cause instanceof Error) ||
            (declaredType != null && declaredType.isInstance(cause));
    }

    /**
     * Returns true if cause of this exception matches one of the declared
     * types or is unchecked.
     */
    public boolean isCauseDeclared(Class declaredType, Class declaredType2) {
        return isCauseDeclared(new Class[] {declaredType, declaredType2});
    }

    /**
     * Returns true if cause of this exception matches one of the declared
     * types or is unchecked.
     */
    public boolean isCauseDeclared(Class... declaredTypes) {
        Throwable cause = getCause();
        if ((cause instanceof RuntimeException) || (cause instanceof Error)) {
            return true;
        }
        if (declaredTypes != null) {
            for (Class declaredType : declaredTypes) {
                if (declaredType != null && declaredType.isInstance(cause)) {
                    return true;
                }
            }
        }
        return false;
    }
}
