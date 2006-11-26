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

import java.rmi.Remote;

import dirmi.info.RemoteInfo;

/**
 * Produces new {@link Skeleton} instances for server-side Remote objects.
 *
 * @author Brian S O'Neill
 * @see SkeletonFactoryGenerator
 */
public interface SkeletonFactory<R extends Remote> {
    /**
     * @return type supported by this skeleton factory
     */
    Class<R> getRemoteType();

    /**
     * @return class that implements Skeleton
     */
    Class<? extends Skeleton> getSkeletonClass();

    /**
     * @param remoteServer server implementation of Remote object
     */
    Skeleton createSkeleton(R remoteServer);
}
