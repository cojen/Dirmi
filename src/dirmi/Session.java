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
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class Session implements Closeable {
    /**
     * Set the timeout for all remote operations on this Session.
     *
     * @param millis timeout value; use negative value for infinite timeout
     */
    //public abstract void setTimeoutMillis(int millis);

    /**
     * Sends a Remote object to the other side of the Session, which must be
     * calling receive.
     *
     * @throws IllegalArgumentException if remote is null
     */
    public abstract void send(Remote remote) throws RemoteException;

    /**
     * Receives a Remote object, blocking until send is called on the other
     * side of the Session.
     */
    public abstract Remote receive() throws RemoteException;

    /**
     * Dispose a Remote object, rendering it unusable for future remote
     * calls. Usually objects need not be explicitly disposed, since the local
     * and remote garbage collectors do so automatically.
     *
     * @throws IllegalArgumentException if remote is null
     */
    public abstract void dispose(Remote object) throws RemoteException;
}
