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
 * Remote method invocation session. Basic communication between sessions is
 * provided by the {@link #send send} and {@link #receive receive} methods,
 * which behave like a blocking queue with a capacity of one.
 *
 * @author Brian S O'Neill
 * @see Environment
 */
public interface Session extends Closeable, Flushable {
    /**
     * @return local session address or null if unknown or not applicable
     */
    Object getLocalAddress();

    /**
     * @return remote session address or null if unknown or not applicable
     */
    Object getRemoteAddress();

    /**
     * Sends a {@link Remote} or {@link Serializable} object to be received by
     * the remote session. Any failure during the send forces the session to be
     * closed.
     *
     * @param obj remote or serializable object to send; can be null
     */
    void send(Object obj) throws RemoteException;

    /**
     * Sends a {@link Remote} or {@link Serializable} object to be received by
     * the remote session. Any non-timeout failure during the send forces the
     * session to be closed.
     *
     * @param obj remote or serializable object to send; can be null
     * @param timeout how long to wait before timing out, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the {@code timeout} parameter 
     * @throws RemoteTimeoutException if timeout elapses
     */
    void send(Object obj, long timeout, TimeUnit unit) throws RemoteException;

    /**
     * Receives a {@link Remote} or {@link Serializable} object sent by the
     * remote session. Any failure during the receive forces the session to be
     * closed.
     *
     * @return remote or serializable object from remote session; can be null
     */
    Object receive() throws RemoteException;

    /**
     * Receives a {@link Remote} or {@link Serializable} object sent by the
     * remote session. Any non-timeout failure during the receive forces the
     * session to be closed.
     *
     * @param timeout how long to wait before timing out, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the {@code timeout} parameter 
     * @return remote or serializable object from remote session; can be null
     * @throws RemoteTimeoutException if timeout elapses
     */
    Object receive(long timeout, TimeUnit unit) throws RemoteException;

    /**
     * Flushes all channels of this session, including channels used for {@link
     * Batched batch} calls and {@link CallMode#EVENTUAL eventual} asynchronous
     * methods.
     */
    void flush() throws IOException;
}
