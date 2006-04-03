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

import java.rmi.Remote;

import dirmi.info.RemoteInfo;

/**
 * 
 *
 * @author Brian S O'Neill
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
     * @param support for accessing other Remote objects
     */
    Skeleton createSkeleton(R remoteServer, SkeletonSupport support);
}
