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

/**
 * Generic exception indicating that a resource is closed.
 *
 * @author Brian S O'Neill
 */
public class ClosedException extends RemoteException {
    public ClosedException() {
        super();
    }

    public ClosedException(String message) {
        super(message);
    }

    public ClosedException(Throwable cause) {
        super(cause.getMessage(), cause);
    }

    public ClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
