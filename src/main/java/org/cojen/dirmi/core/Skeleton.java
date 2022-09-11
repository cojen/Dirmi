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

import java.io.IOException;

import org.cojen.dirmi.Pipe;

/**
 * A Skeleton instance wraps a server-side Remote object, unmarshals client requests, and
 * invokes server-side methods. Any response is marshaled back to the client.
 *
 * @author Brian S O'Neill
 * @see SkeletonFactory
 */
public abstract class Skeleton<R> extends Item {
    public Skeleton(long id) {
        super(id);
    }

    public abstract Class<R> type();

    public abstract long typeId();

    /**
     * Returns the Remote object managed by this Skeleton.
     */
    public abstract R server();

    /**
     * Invoke a remote method on the server. Any exception thrown from the invoked method is
     * written to the pipe, unless the method is asynchronous. Any other exception thrown from
     * this method indicates a communication failure, and so the pipe should be closed.
     *
     * @param pipe pipe for reading method arguments and for writing response.
     * @return the original or modified context; caller should stop reading if is STOP_READING
     * @see BatchedContext
     */
    public abstract Object invoke(Pipe pipe, Object context) throws IOException;
}
