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

import java.io.IOException;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;

/**
 * A Skeleton instance wraps a server-side Remote object, unmarshalls client
 * requests, and invokes server-side methods. Any response is marshalled back
 * to the client.
 *
 * @author Brian S O'Neill
 * @see SkeletonFactory
 */
public interface Skeleton {
    /**
     * Invoke method in server-side instance. Any exception thrown from the
     * invoked method is written to the connection, unless method is
     * asynchronous. Any other exception thrown from this method indicates a
     * communication failure, and so the connection should be closed.
     *
     * @param con RemoteConnection for reading method identifier and arguments,
     * and for writing response.
     * @throws IOException if thrown from connection
     * @throws NoSuchMethodException if method is unknown
     * @throws NoSuchObjectException if remote parameter refers to an unknown object
     * @throws ClassNotFoundException if unmarshalling an object parameter
     * refers to an unknown class
     * @throws AsynchronousInvocationException if method is asynchronous and
     * throws an exception
     */
    void invoke(RemoteConnection con)
        throws IOException,
               NoSuchMethodException,
               NoSuchObjectException,
               ClassNotFoundException,
               AsynchronousInvocationException;

    /**
     * Returns the Remote object wrapped by this Skeleton.
     */
    Remote getRemoteObject();
}
