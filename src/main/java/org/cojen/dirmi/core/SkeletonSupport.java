/*
 *  Copyright 2022 Cojen.org
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

package org.cojen.dirmi.core;

import org.cojen.dirmi.Pipe;

/**
 * Object passed to a Skeleton instance in order for it to decide when pipes can be reused.
 *
 * @author Brian S O'Neill
 */
public interface SkeletonSupport {
    /**
     * Used by batched methods which return a Remote object. This method creates a skeleton for
     * the given remote object and registers it.
     *
     * @param skeleton skeleton which was invoked
     * @param skeletonMethodName name of skeleton method which was invoked
     * @param typeId type ID assigned by client
     * @param remoteId object ID assigned by client
     * @param type type of remote object
     * @param remote remote object returned by server method
     */
    /* FIXME: linkBatchedRemote
    <R extends Remote> void linkBatchedRemote(Skeleton skeleton,
                                              String skeletonMethodName,
                                              Identifier typeId, VersionedIdentifier remoteId,
                                              Class<R> type, R remote)
        throws RemoteException;
    */

    /**
     * Called by a disposer method when finished executing. This method itself should not throw
     * any exceptions.
     */
    void dispose(Skeleton<?> skeleton);
}
