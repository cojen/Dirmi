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

import java.io.IOException;

/**
 * Listener is called asynchronously as sessions are established.
 *
 * @author Brian S O'Neill
 * @see SessionAcceptor
 */
public interface SessionListener {
    /**
     * Called at most once as soon as session has been established. This method
     * may safely block, and it can interact with the session too. Any checked
     * exception thrown from this method is passed to the failed method.
     */
    void established(Session session) throws IOException;

    /**
     * Called when session cannot be established. Exception can be rethrown, in
     * which case it is passed to the thread's uncaught exception handler.
     */
    void failed(IOException e) throws IOException;
}
