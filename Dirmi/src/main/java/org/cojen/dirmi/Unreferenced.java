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

/**
 * By implementing this interface (or the superinterface), a remote object
 * implementation receives notification when a {@link Session} no longer
 * references it. If the object is shared by multiple sessions, multiple
 * notifications can be received. An object can be declared unreferenced by the
 * remote garbage collector, or when a Session is closed. Sessions can be
 * closed explicitly, but they are also closed when the remote endpoint is no
 * longer reachable.
 *
 * @author Brian S O'Neill
 */
public interface Unreferenced extends java.rmi.server.Unreferenced {
    /**
     * Called by Dirmi when the remote object is not referenced by a Session.
     */
    @Override
    void unreferenced();
}
