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

/**
 * Thrown when attempting to invoke a method against a disposed object.
 *
 * @author Brian S O'Neill
 * @see Disposer
 */
public class DisposedException extends ClosedException {
    public DisposedException() {
        super();
    }

    public DisposedException(String message) {
        super(message);
    }

    public DisposedException(Throwable cause) {
        super(cause.getMessage(), cause);
    }

    public DisposedException(String message, Throwable cause) {
        super(message, cause);
    }
}
