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

package dirmi.core;

import org.cojen.util.ThrowUnchecked;

/**
 * If an exception is thrown from a skeleton-invoked method which is batched,
 * it must be thrown from the first non-batched method encountered in the
 * batch stream.
 *
 * @author Brian S O'Neill
 */
public class BatchedInvocationException extends Exception {
    private static final long serialVersionUID = 1;

    public BatchedInvocationException(Throwable cause) {
        super(cause);
    }

    /**
     * Throws the cause of this exception if it is unchecked or an instance of
     * any of the given declared type. Otherwise, it is thrown as an
     * UndeclaredThrowableException. This method only returns normally if the
     * cause is null.
     */
    public void throwAsDeclaredCause(Class declaredType) {
        ThrowUnchecked.fireDeclared(getCause(), declaredType);
    }

    /**
     * Throws the cause of this exception if it is unchecked or an instance of
     * any of the given declared types. Otherwise, it is thrown as an
     * UndeclaredThrowableException. This method only returns normally if the
     * cause is null.
     */
    public void throwAsDeclaredCause(Class declaredType1, Class declaredType2) {
        ThrowUnchecked.fireDeclared(getCause(), declaredType1, declaredType2);
    }

    /**
     * Throws the cause of this exception if it is unchecked or an instance of
     * any of the given declared types. Otherwise, it is thrown as an
     * UndeclaredThrowableException. This method only returns normally if the
     * cause is null.
     */
    public void throwAsDeclaredCause(Class... declaredTypes) {
        ThrowUnchecked.fireDeclared(getCause(), declaredTypes);
    }
}
