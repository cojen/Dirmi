/*
 *  Copyright 2022 Cojen.org
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

import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import java.util.Objects;

import java.util.concurrent.Executor;

import org.cojen.dirmi.core.CoreUtils;
import org.cojen.dirmi.core.Engine;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface Environment extends Closeable {
    /**
     * Returns a new {@code Environment} instance which uses a default {@link Executor}.
     */
    static Environment create() {
        return new Engine();
    }

    /**
     * Returns a new {@code Environment} instance which uses the given {@link Executor}.
     */
    static Environment create(Executor executor) {
        Objects.requireNonNull(executor);
        return new Engine(executor);
    }

    /**
     * Export a named server-side object. If replacing an existing object, then the previously
     * exported instance is disposed of. Pass a null object to remove an export completely.
     * Replacing or removing an exported object has no effect on sessions which already
     * exported it.
     *
     * @param name serializable name
     * @param obj remote object
     * @return the previously exported object or null if none
     * @throws IllegalArgumentException if the name isn't serializable or if the object to
     * export isn't {@link Remote remote}.
     */
    Object export(Object name, Object obj) throws IOException;

    /**
     * Accept all server-side connections from the given {@code ServerSocket}.
     *
     * @return an object which can be closed to stop accepting
     * @throws IllegalStateException if already accepting connections from the socket
     */
    Closeable acceptAll(ServerSocket ss) throws IOException;

    /**
     * Accept all server-side connections from the given {@code ServerSocketChannel}.
     *
     * @return an object which can be closed to stop accepting
     * @throws IllegalStateException if already accepting connections from the socket channel
     */
    Closeable acceptAll(ServerSocketChannel ss) throws IOException;

    /**
     * Call when a server-side connection has been explicitly accepted. Any exception thrown
     * from this method closes the socket.
     *
     * @return new or existing server-side session instance
     * @throws IOException if a communication failure or if the client is requesting an object
     * which isn't exported
     */
    default Session<?> accepted(Socket s) throws IOException {
        CoreUtils.setOptions(s);
        return accepted(s.getInputStream(), s.getOutputStream());
    }

    /**
     * Call when a server-side connection has been explicitly accepted. Any exception thrown
     * from this method closes the socket.
     *
     * @return new or existing server-side session instance
     * @throws IOException if a communication failure or if the client is requesting an object
     * which isn't exported
     */
    default Session<?> accepted(SocketChannel s) throws IOException {
        CoreUtils.setOptions(s);
        return accepted(Channels.newInputStream(s), Channels.newOutputStream(s));
    }

    /**
     * Call when a server-side connection has been explicitly accepted. Any exception thrown
     * from this method closes the socket.
     *
     * @return new or existing server-side session instance
     * @throws IOException if a communication failure or if the client is requesting an object
     * which isn't exported
     */
    Session<?> accepted(InputStream in, OutputStream out) throws IOException;

    /**
     * Call to establish a new client-side session. A remote server must be accepting
     * connections and it must also export the given named object.
     *
     * @param type the type of the root object which is exported by the remote server
     * @param name the name of the root object which is exported by the remote server
     * @param addr server address to connect to
     * @return new or existing client-side session instance
     * @throws IllegalArgumentException if the name isn't serializable or if the type isn't
     * {@link Remote remote}.
     * @throws IOException if a communication failure or if no object is exported by the given
     * type and name
     */
    <R> Session<R> connect(Class<R> type, Object name, SocketAddress addr) throws IOException;

    /**
     * Call to establish a new client-side session. A remote server must be accepting
     * connections and it must also export the given named object.
     *
     * @param name the name of the root object which is exported by the remote server
     * @param host server host address to connect to
     * @param port server port to connect to
     * @return new or existing client-side session instance
     * @throws IllegalArgumentException if the name isn't serializable or if the type isn't
     * {@link Remote remote}.
     * @throws IOException if a communication failure or if no object is exported by the given
     * type and name
     */
    default <R> Session<R> connect(Class<R> type, Object name, String host, int port)
        throws IOException
    {
        return connect(type, name, new InetSocketAddress(host, port));
    }

    /**
     * Assign a connector for establishing new client-side sessions. By default, the {@link
     * Connector#direct direct} connector is used.
     *
     * @param c non-null connector
     * @return the previously assigned connector
     */
    Connector connector(Connector c) throws IOException;

    /**
     * Resets all active sessions.
     *
     * @see Session#reset
     */
    void reset();

    /**
     * Stops accepting new sessions, closes all existing sessions, and disposes of all exported
     * objects.
     */
    @Override
    void close();
}
