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

import java.net.SocketAddress;

/**
 * Represents an established connection between two endpoints.
 *
 * @author Brian S O'Neill
 */
public interface Link {
    /**
     * Returns the local link address, or null if unknown or not applicable.
     */
    SocketAddress localAddress();

    /**
     * Returns the remote link address, or null if unknown or not applicable.
     */
    SocketAddress remoteAddress();
}
