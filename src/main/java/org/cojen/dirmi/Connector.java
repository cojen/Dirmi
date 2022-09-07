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

package org.cojen.dirmi;

import java.io.IOException;

import java.net.SocketAddress;

import org.cojen.dirmi.core.DirectConnector;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@FunctionalInterface
public interface Connector {
    /**
     * Returns a default connector that directly connects the socket.
     */
    static Connector direct() {
        return DirectConnector.THE;
    }

    /**
     * Called to establish a new connection, which is then passed to the given session.
     */
    void connect(Session<?> s, SocketAddress address) throws IOException;
}
