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

package org.cojen.dirmi.core;

import java.rmi.Remote;
import java.rmi.server.Unreferenced;

import org.cojen.dirmi.util.Identifier;
import org.cojen.dirmi.util.VersionedIdentifier;

/**
 * Factory which creates skeletons that support no methods.
 *
 * @author Brian S O'Neill
 */
class EmptySkeletonFactory implements SkeletonFactory {
    static final EmptySkeletonFactory THE = new EmptySkeletonFactory();

    private EmptySkeletonFactory() {
    }

    public Skeleton createSkeleton(SkeletonSupport support, final Remote remoteServer) {
        return new Skeleton() {
            public Remote getRemoteServer() {
                return remoteServer;
            }

            public boolean invoke(Identifier methodID,
                                  InvocationChannel channel, BatchedInvocationException exception)
                throws NoSuchMethodException
            {
                throw new NoSuchMethodException("method id: " + methodID);
            }

            public void unreferenced() {
                if (remoteServer instanceof Unreferenced) {
                    ((Unreferenced) remoteServer).unreferenced();
                }
            }
        };
    }
}
