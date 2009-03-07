/*
 *  Copyright 2009 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.Closeable;
import java.io.IOException;

import java.util.concurrent.TimeUnit;

/**
 * Supports direct channel connection to a remote endpoint. All channels are
 * linked to the same remote endpoint.
 *
 * @author Brian S O'Neill
 */
public interface Connector<C extends Closeable> {
    /**
     * @return remote address of connected channels or null if unknown
     */
    Object getRemoteAddress();

    /**
     * @return local address of connected channels or null if unknown
     */
    Object getLocalAddress();

    /**
     * Returns a new channel, possibly blocking until it has been established.
     */
    C connect() throws IOException;

    /**
     * Returns a new channel, possibly blocking until it has been established.
     */
    C connect(long timeout, TimeUnit unit) throws IOException;
}
