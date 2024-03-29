/*
 *  Copyright 2006-2022 Cojen.org
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

/**
 * Produces new {@link Skeleton} instances for server-side Remote objects.
 *
 * @author Brian S O'Neill
 */
public interface SkeletonFactory<R> {
    long typeId();

    /**
     * @param id remote object identifier
     * @param support for reusing pipes
     * @param server implementation of Remote object
     */
    Skeleton<R> newSkeleton(long id, SkeletonSupport support, R server);

    /**
     * Constructs a broken skeleton.
     *
     * @param exception exception to throw when attempting to invoke methods
     * @param id remote object identifier
     * @param support for reusing pipes
     */
    Skeleton<R> newSkeleton(Throwable exception, long id, SkeletonSupport support);
}
