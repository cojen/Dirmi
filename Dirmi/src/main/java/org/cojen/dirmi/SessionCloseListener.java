/*
 *  Copyright 2011 Brian S O'Neill
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

/**
 * Receives notification when a {@link Session} is closed.
 *
 * @author Brian S O'Neill
 * @see Session#addCloseListener(SessionCloseListener)
 */
public interface SessionCloseListener {
    public static enum Cause {
        /** Session was explicitly closed by local endpoint. */
        LOCAL_CLOSE,

        /** Session was explicitly closed by remote endpoint. */
        REMOTE_CLOSE,

        /** Session was closed due to communication failure with remote endpoint. */
        COMMUNICATION_FAILURE;
    };

    /**
     * Called at most once by a session, after it has closed.
     *
     * @param sessionLink session link which was closed
     * @param cause reason why session was closed
     */
    void closed(Link sessionLink, Cause cause);
}
