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

package org.cojen.dirmi;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.Serializable;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

/**
 * Remote method invocation session. After a session is established, call an
 * {@link #exchange exchange} method to send/receive primary remote
 * objects. Client sessions usually pass null to the exchange method, and
 * server sessions pass a remote server instance.
 *
 * @author Brian S O'Neill
 * @see Environment
 */
public interface Session extends Closeable, Flushable {
    /**
     * Exchanges a {@link Remote} or {@link Serializable} object with the
     * remote session. This method blocks if remote session is not exchanging as
     * well. Any failure during the exchange forces the session to be closed.
     *
     * @param obj remote or serializable object to exchange; can be null
     * @return remote or serializable object from remote session; can be null
     */
    Object exchange(Object obj) throws RemoteException;

    /**
     * Exchanges a {@link Remote} or {@link Serializable} object with the
     * remote session. This method blocks if remote session is not exchanging as
     * well. Any failure during the exchange forces the session to be closed.
     *
     * @param obj remote or serializable object to exchange; can be null
     * @param timeout how long to wait before closing session, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the {@code timeout} parameter 
     * @return remote or serializable object from remote session; can be null
     */
    Object exchange(Object obj, long timeout, TimeUnit unit) throws RemoteException;

    /**
     * @return local session address or null if unknown or not applicable
     */
    Object getLocalAddress();

    /**
     * @return remote session address or null if unknown or not applicable
     */
    Object getRemoteAddress();

    /**
     * Flushes all channels of this session, including channels used for {@link
     * Batched batch} calls and {@link CallMode#EVENTUAL eventual} asynchronous
     * methods.
     */
    void flush() throws IOException;
}
