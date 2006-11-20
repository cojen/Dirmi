/*
 *  Copyright 2006 Brian S O'Neill
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

import java.rmi.RemoteException;

import dirmi.io.RemoteConnection;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see StubFactory
 */
public interface StubSupport {
    /**
     * @param methodID ID of method in remote object
     * @return RemoteConnection for writing arguments and reading response. If call
     * is synchronous, output is flushed after arguments are written, and then
     * connection is read from. If call is asynchronous, connection is closed
     * after arguments are written.
     */
    RemoteConnection invoke(Identifier methodID) throws RemoteException;

    void dispose() throws RemoteException;
}
