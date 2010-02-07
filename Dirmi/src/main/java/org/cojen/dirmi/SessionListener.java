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

import java.io.IOException;

/**
 * Listener is called asynchronously as sessions are established.
 *
 * @author Brian S O'Neill
 * @see SessionAcceptor
 */
public interface SessionListener {
    /**
     * Called at most once as soon as session has been established.
     * Implementation must ensure that {@link SessionAcceptor#accept accept} is
     * called to accept new sessions. Afterwards, this method may safely block,
     * and it can interact with the session too.
     *
     * @throws IOException any thrown exception forces the session to close,
     * and the cause is passed to the thread's uncaught exception handler.
     */
    void established(Session session) throws IOException;

    /**
     * Called when session channel was accepted, but session was terminated
     * before establishment completed. Implementation must ensure that {@link
     * SessionAcceptor#accept accept} is called to accept new sessions.
     *
     * @throws IOException cause can be rethrown, but it is passed to the
     * thread's uncaught exception handler.
     */
    void establishFailed(IOException cause) throws IOException;

    /**
     * Called when session channel cannot be accepted. Implementation should
     * not call {@link SessionAcceptor#accept accept} again, because no new
     * sessions can be accepted.
     *
     * @throws IOException cause can be rethrown, but it is passed to the
     * thread's uncaught exception handler.
     */
    void acceptFailed(IOException cause) throws IOException;
}
