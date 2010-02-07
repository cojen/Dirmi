/*
 *  Copyright 2006-2010 Brian S O'Neill
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
 * which behave like a blocking queue with a capacity of one. Object transport
 * via this interface is intended for handshaking and authentication. After an
 * initial exchange, remote objects should be used for most communication
 * between endpoints.
 *
 * <p>The send/receive methods follow the pull model rather than the push
 * model. Calling {@code send} locally enqueues an object, but {@code receive}
 * makes a remote call. This behavior allows a {@link #setClassResolver
 * ClassResolver} to be installed prior to its first use, avoiding race
 * conditions.
 *
 * @author Brian S O'Neill
 * @see Environment
 */
public interface Session extends Closeable, Flushable, Link {
    /**
     * @return local session address or null if unknown or not applicable
     */
    @Override
    Object getLocalAddress();

    /**
     * @return remote session address or null if unknown or not applicable
     */
    @Override
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
     * the remote session. Any failure or timeout during the send forces the
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
     * remote session. Any failure or timeout during the receive forces the
     * session to be closed.
     *
     * @param timeout how long to wait before timing out, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the {@code timeout} parameter 
     * @return remote or serializable object from remote session; can be null
     * @throws RemoteTimeoutException if timeout elapses
     */
    Object receive(long timeout, TimeUnit unit) throws RemoteException;

    /**
     * Can be called at most once to control how deserialized classes and
     * remote interfaces are resolved. If the implemention chooses to remotely
     * download classes, it is strongly recommended that a security manager be
     * installed. Resolver implementation may choose to enforce this
     * restriction.
     *
     * @param resolver resolves deserialized classes and interfaces; pass null
     * to always use default resolver
     * @throws IllegalStateException if resolver cannot be changed
     */
    void setClassResolver(ClassResolver resolver);

    /**
     * Convenience method to use a ClassLoader for resolving classes.
     *
     * @param loader resolves deserialized classes and interfaces; pass null
     * to always use default resolver
     * @throws IllegalStateException if resolver cannot be changed
     */
    void setClassLoader(ClassLoader loader);

    /**
     * Flushes all channels of this session, including channels used for {@link
     * Batched batch} calls and {@link CallMode#EVENTUAL eventual} asynchronous
     * methods.
     */
    @Override
    void flush() throws IOException;

    /**
     * Closes the session.
     */
    @Override
    void close() throws IOException;
}
