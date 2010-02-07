/*
 *  Copyright 2006-2010 Brian S O'Neill
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

import java.rmi.RemoteException;

/**
 * Thrown when a client is requesting information about a class that is unknown
 * to the server.
 *
 * @author Brian S O'Neill
 */
public class NoSuchClassException extends RemoteException {
    private static final long serialVersionUID = 1;

    public NoSuchClassException(String message) {
        super(message);
    }

    public NoSuchClassException(String message, Throwable cause) {
        super(message, cause);
    }
}
