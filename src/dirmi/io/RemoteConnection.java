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

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteConnection extends Connection {
    /**
     * Blocks until peer writes a remote object.
     *
     * @return remote object, which is either a stub or an existing local object
     */
    Remote readRemote() throws RemoteException;

    /**
     * Send an object to the remote peer. If peer is not reading remote
     * objects, then this call may block.
     *
     * @param remote remote object to write, which may be locally created or a stub
     */
    void writeRemote(Remote remote) throws RemoteException;

    /**
     * Dispose an object that was read or written, rendering it unusable for
     * future remote calls. If objects are not disposed, they are ineligible
     * for garbage collection. Usually objects need not be explicitly disposed,
     * since the local and remote garbage collectors so do automatically.
     *
     * @return true if object disposed, false if object was already disposed or is unknown
     */
    boolean dispose(Remote remote) throws RemoteException;
}
