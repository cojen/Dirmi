/*
 *  Copyright 2008 Brian S O'Neill
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

import java.rmi.Remote;
import java.rmi.RemoteException;

import dirmi.util.Identifier;
import dirmi.util.VersionedIdentifier;

/**
 * Object passed to a Skeleton instance in order for it to decide when channels
 * can be reused.
 *
 * @author Brian S O'Neill
 */
public interface SkeletonSupport {
    /**
     * Used by batched methods which return a Remote object. This method
     * creates a skeleton for the given remote object and registers it.
     *
     * @param typeID type ID assigned by client
     * @param remoteID object ID assigned by client
     * @param type type of remote object
     * @param remote remote object returned by server method
     */
    <R extends Remote> void linkBatchedRemote(Identifier typeID, VersionedIdentifier remoteID,
                                              Class<R> type, R remote)
        throws RemoteException;

    /**
     * Used by batched methods which return a Remote object. This method is
     * called if server method throws an exception.
     *
     * @param type type of remote object
     * @param cause thrown by server method
     * @return instance of remote object which throws the cause from every method
     */
    <R extends Remote> R failedBatchedRemote(Class<R> type, Throwable cause);

    /**
     * Called after channel usage is finished and can be reused for receiving
     * new requests. This method should not throw any exception.
     *
     * @param synchronous pass true for synchronous method
     * @return true if caller should read another request from channel
     */
    boolean finished(InvocationChannel channel, boolean synchronous);
}
