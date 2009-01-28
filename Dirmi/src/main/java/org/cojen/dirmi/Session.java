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

package org.cojen.dirmi;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.Serializable;

import java.rmi.Remote;

/**
 * Remote method invocation session.
 *
 * @author Brian S O'Neill
 * @see Environment
 */
public interface Session extends Closeable, Flushable {
    /**
     * Returns the primary {@link Remote} or {@link Serializable} object
     * exported by remote endpoint.
     *
     * @return primary remote server object, which can be null
     */
    Object getRemoteServer();

    /**
     * @return remote session address or null if unknown or not applicable
     */
    Object getRemoteAddress();

    /**
     * Returns the primary {@link Remote} or {@link Serializable} object
     * exported by local endpoint.
     *
     * @return primary local server object, which can be null
     */
    Object getLocalServer();

    /**
     * @return local session address or null if unknown or not applicable
     */
    Object getLocalAddress();

    /**
     * Flushes all channels of this session, including channels used for {@link
     * Batched batch} calls and {@link CallMode#EVENTUAL eventual} asynchronous
     * methods.
     */
    void flush() throws IOException;
}
