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

package dirmi.io;

import java.io.IOException;
import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Basic interface for a bidirectional remote I/O connection.
 *
 * @author Brian S O'Neill
 */
public interface RemoteConnection extends Connection, RemoteBroker {
    RemoteInput getRemoteInput() throws IOException;

    RemoteOutput getRemoteOutput() throws IOException;

    // FIXME: add method to create identifier for any object (identifier is Externalizable)
    // FIXME: add method to get object from identifier
    // FIXME: dispose should work on any kind of identified object

    /**
     * Dispose an object that was read or written, rendering it unusable for
     * future remote calls. If objects are not disposed, they are ineligible
     * for garbage collection. Usually objects need not be explicitly disposed,
     * since the local and remote garbage collectors do so automatically.
     *
     * @return true if object disposed, false if object was already disposed or is unknown
     */
    boolean dispose(Remote remote) throws RemoteException;
}
