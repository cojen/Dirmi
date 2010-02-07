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

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Thrown when a remote method has not been implemented by the remote
 * server. This can happen when the client and server have different versions
 * of the interface that extends {@link Remote}.
 *
 * @author Brian S O'Neill
 */
public class UnimplementedMethodException extends RemoteException {
    private static final long serialVersionUID = 1;

    public UnimplementedMethodException(String message) {
        super(message);
    }
}
