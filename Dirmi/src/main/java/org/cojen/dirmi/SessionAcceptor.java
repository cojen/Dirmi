/*
 *  Copyright 2009-2010 Brian S O'Neill
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
import java.io.IOException;

/**
 * Accepts sessions from remote endpoints.
 *
 * @author Brian S O'Neill
 * @see Environment
 */
public interface SessionAcceptor extends Closeable {
    /**
     * Returns immediately and starts automatically accepting all sessions
     * asynchronously, sending a shared object. No attempt is made to receive
     * objects sent by remote client sessions. Exceptions thrown during session
     * establishment are discarded. The {@link #accept(SessionListener) accept}
     * method may be called at any time to switch to manual session acceptance.
     *
     * @param shared shared remote or serializable object to send to accepted
     * sessions
     */
    void acceptAll(Object shared);

    /**
     * Returns immediately and calls listener asynchronously, but no more than
     * once. The accept method must be called again (possibly with the same
     * listener instance) in order to accept more sessions. If no listener is
     * accepting incoming sessions, then the session is closed after a timeout
     * elapses. The {@link #acceptAll acceptAll} method may be called at any
     * time to switch to automatic session acceptance.
     */
    void accept(SessionListener listener);

    /**
     * @return local address of accepted sessions or null if unknown
     */
    Object getLocalAddress();

    /**
     * Prevents new sessions from being accepted and closes all existing
     * sessions created from this acceptor.
     */
    void close() throws IOException;
}
