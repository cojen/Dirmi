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

package dirmi.core;

import java.rmi.RemoteException;

import dirmi.io.RemoteConnection;

/**
 * Object passed to a Stub instance in order for it to actually communicate
 * with a remote object.
 *
 * @author Brian S O'Neill
 * @see StubFactory
 */
public interface StubSupport {
    /**
     * @return RemoteConnection for writing method identifier and arguments,
     * and for reading response. If call is synchronous, output is flushed
     * after arguments are written, and then connection is read from. If call
     * is asynchronous, connection is closed after arguments are written.
     */
    RemoteConnection invoke() throws RemoteException;

    /**
     * Dispose remotely identified object, rendering it unusable for future
     * remote calls. Usually objects need not be explicitly disposed, since the
     * local and remote garbage collectors do so automatically.
     */
    void dispose() throws RemoteException;
}
