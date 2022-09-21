/*
 *  Copyright 2011-2022 Cojen.org
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

/**
 * Remote objects implementing this interface are notified when they are attached and detached
 * from sessions.
 *
 * @author Brian S O'Neill
 */
public interface SessionAware {
    /**
     * Called when this object has been attached to a session and can be accessed remotely.
     */
    void attached(Session s);

    /**
     * Called when this object has been detached from a session which it was previously
     * attached to.
     */
    void detached(Session s);
}
