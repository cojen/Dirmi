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

import java.io.Closeable;
import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface SessionAcceptor extends Closeable {
    /**
     * Returns immediately and calls established method on listener
     * asynchronously. Only one session is accepted per invocation of this
     * method.
     *
     * @param server optional server object to export
     * @param listener listener of session creation
     */
    void accept(Object server, SessionListener listener);

    /**
     * Closes the session acceptor, but does not close established sessions.
     */
    void close() throws IOException;
}
