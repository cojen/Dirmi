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

package dirmi;

import java.io.Closeable;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote method invocation session.
 *
 * @author Brian S O'Neill
 */
public interface Session extends Closeable {
    /**
     * Returns the main remote server object, which may be null.
     *
     * @return main remote server object, or null 
     */
    Object getRemoteServer();

    /**
     * Closes the session.
     */
    void close() throws RemoteException;
}
