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

package dirmi;

import java.io.Closeable;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote Method Invocation Session.
 *
 * @author Brian S O'Neill
 */
public interface Session extends Closeable {
    /**
     * Returns the main remote server object, which may be null.
     *
     * @return main remote server object, or null 
     */
    Object getRemoteServer();

    /**
     * Returns the client-side timeout for sending method requests, in
     * milliseconds. A negative timeout is infinite. When a request times out,
     * it throws a {@link RequestTimeoutException}.
     */
    int getSendRequestTimeout();

    /**
     * Set the client-side timeout for sending method requests, in
     * milliseconds. A negative timeout is infinite. When a request times out,
     * it throws a {@link RequestTimeoutException}.
     */
    void setSendRequestTimeout(int timeoutMillis);

    /**
     * Returns the client-side timeout for receiving method responses, in
     * milliseconds. A negative timeout is infinite. When a response times out,
     * it throws a {@link ResponseTimeoutException}.
     */
    int getReceiveResponseTimeout();

    /**
     * Set the client-side timeout for receiving method responses, in
     * milliseconds. A negative timeout is infinite. When a response times out,
     * it throws a {@link ResponseTimeoutException}.
     */
    void setReceiveResponseTimeout(int timeoutMillis);

    /**
     * Returns the server-side timeout for receiving method requests, in
     * milliseconds. A negative timeout is infinite. When receiving a request
     * times out, a warning is logged.
     */
    int getReceiveRequestTimeout();

    /**
     * Set the server-side timeout for receiving method requests, in
     * milliseconds. A negative timeout is infinite. When receiving a request
     * times out, a warning is logged.
     */
    void setReceiveRequestTimeout(int timeoutMillis);

    /**
     * Returns the server-side timeout for sending method responses, in
     * milliseconds. A negative timeout is infinite. When sending a response
     * times out, a warning is logged.
     */
    int getSendResponseTimeout();

    /**
     * Set the server-side timeout for sending method responses, in
     * milliseconds. A negative timeout is infinite. When sending a response
     * times out, a warning is logged.
     */
    void setSendResponseTimeout(int timeoutMillis);

    /**
     * Closes the session.
     */
    void close() throws RemoteException;

    /**
     * Dispose a Remote object, rendering it unusable for future remote
     * calls. Usually objects need not be explicitly disposed, since the local
     * and remote garbage collectors do so automatically.
     *
     * @throws IllegalArgumentException if remote is null
     */
    void dispose(Remote object) throws RemoteException;
}
