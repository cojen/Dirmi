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

package org.cojen.dirmi;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;

import java.rmi.Remote;

import org.cojen.dirmi.io.AcceptListener;
import org.cojen.dirmi.io.Acceptor;

/**
 * Accepts sessions from remote endpoints.
 *
 * @author Brian S O'Neill
 * @see Environment
 */
public interface SessionAcceptor extends Acceptor<Session>, Closeable {
    /**
     * Returns immediately and starts automatically accepting all sessions
     * asynchronously. Any exceptions during session establishment are passed
     * to the thread's uncaught exception handler. The {@link
     * #accept(AcceptListener) accept} method may be called to switch to manual
     * session acceptance.
     */
    void acceptAll();

    /**
     * Returns immediately and calls established method on listener
     * asynchronously. Only one session is accepted per invocation of this
     * method. If no listener is accepting incoming sessions, then the session
     * is closed after a timeout elapses. The {@link #acceptAll acceptAll}
     * method may be called to switch to automatic session acceptance.
     */
    void accept(AcceptListener<Session> listener);

    /**
     * Returns the primary {@link Remote} or {@link Serializable} object
     * exported by local endpoint.
     *
     * @return primary local server object, which can be null
     */
    Object getLocalServer();

    /**
     * @return local address of accepted sessions or null if unknown
     */
    Object getLocalAddress();

    /**
     * Prevents new sessions from being accepted and closes all existing sessions.
     */
    void close() throws IOException;
}
