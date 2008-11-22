/*
 *  Copyright 2008 Brian S O'Neill
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

import java.io.IOException;

/**
 * Callback invoked by {@link Environment} as sessions are accepted.
 *
 * @author Brian S O'Neill
 */
public interface SessionAcceptor {
    /**
     * Called by multiple threads as a session is being
     * established. Implementation should return a Remote or Serializable
     * object which is exported to remote endpoint. The object returned by this
     * method is available as the session's local server.
     *
     * @return primary local server object
     */
    Object createServer();

    /**
     * Called by multiple threads as session are established. This method may
     * safely block, and it can interact with the session too.
     */
    void established(Session session);

    /**
     * Called by multiple threads as sessions cannot be established. This
     * method may safely block.
     */
    void failed(IOException e);
}
