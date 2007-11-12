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
     *
     * @throws java.rmi.NoSuchObjectException if support has been disposed
     */
    RemoteConnection invoke() throws RemoteException;

    /**
     * Forcibly close connection and don't throw any exception.
     */
    void forceConnectionClose(RemoteConnection con);

    /**
     * Returns a hashCode implementation for the Stub.
     */
    int stubHashCode();

    /**
     * Returns a partial equals implementation for the Stub.
     */
    boolean stubEquals(StubSupport support);

    /**
     * Returns a partial toString implementation for the Stub.
     */
    String stubToString();
}
