/*
 *  Copyright 2008-2022 Cojen.org
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

import java.io.IOException;

import org.cojen.dirmi.Pipe;

/**
 * Object passed to a Skeleton instance in order for it to decide when pipes can be reused.
 *
 * @author Brian S O'Neill
 */
public interface SkeletonSupport {
    /**
     * Used by batched methods for creating a skeleton that can be found by the given alias
     * identifier.
     *
     * @param aliasId negative identifier provided by the client
     */
    void createSkeletonAlias(Object server, long aliasId);

    /**
     * Used by batched immediate methods for writing a Remote object.
     *
     * @param aliasId negative identifier provided by the client
     */
    void writeSkeletonAlias(Pipe pipe, Object server, long aliasId) throws IOException;

    /**
     * Used by batched methods which cannot call createSkeletonAlias because of a prior
     * exception. The client must disposed of the stub because it's not linked to anything.
     */
    void writeDisposed(Pipe pipe, long id, Object reason) throws IOException;

    /**
     * Called by a disposer method when finished executing. This method itself should not throw
     * any exceptions.
     */
    void dispose(Skeleton<?> skeleton);
}
