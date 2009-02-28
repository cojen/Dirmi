/*
 *  Copyright 2009 Brian S O'Neill
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

/**
 * Thrown if a connection is refused to the remote host for a remote method call.
 *
 * @author Brian S O'Neill
 */
public class ConnectException extends java.rmi.ConnectException {
    public ConnectException(String message) {
        super(message);
    }

    public ConnectException(String message, Exception cause) {
        super(message, cause);
    }
}
