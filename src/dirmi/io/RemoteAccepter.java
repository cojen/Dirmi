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

package dirmi.io;

import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteAccepter extends Accepter {
    /**
     * Called by server to block waiting for a new connection request from client.
     *
     * @return new connection
     */
    RemoteConnection accept() throws IOException;

    /**
     * Called by server to block waiting for a new connection request from client.
     *
     * @return new connection, or null if timed out
     */
    RemoteConnection accept(int timeoutMillis) throws IOException;
}
