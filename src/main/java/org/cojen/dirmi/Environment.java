/*
 *  Copyright 2008-2022 Cojen.org
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
import java.util.concurrent.ExecutorService;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.cojen.dirmi.core.ClassLoaderResolver;
import org.cojen.dirmi.core.CoreUtils;
import org.cojen.dirmi.core.Engine;

/**
 * Sharable environment for connecting and accepting remote sessions.
 *
 * @author Brian S O'Neill
 */
public interface Environment extends Closeable, Executor {
    /**
     * Returns a new {@code Environment} instance which uses a default {@link Executor}. When
     * the {@code Environment} is closed, the {@code Executor} is also closed.
     */
    static Environment create() {
        return new Engine(null, true);
    }

    /**
     * Returns a new {@code Environment} instance which uses the given {@link Executor}. When
     * the {@code Environment} is closed, the {@code Executor} is not closed.
     */
    static Environment create(Executor executor) {
        return create(executor, false);
    }

    /**
     * Returns a new {@code Environment} instance which uses the given {@link Executor}.
     *
     * @param closeExecutor when true, the {@code Executor} is closed when the {@code
     * Environment} is closed
     * @throws IllegalArgumentException if {@code closeExecutor} is true and the given {@code
     * Executor} doesn't implement {@link AutoCloseable} or {@link ExecutorService}
     * @throws NullPointerException if the given executor is null
     */
    static Environment create(Executor executor, boolean closeExecutor) {
        Objects.requireNonNull(executor);
        return new Engine(executor, closeExecutor);
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
     * Accept all server-side connections from the given {@code ServerSocket}. As long as the
     * acceptor is still running, the JVM won't exit.
     *
     * @return an object which can be closed to stop accepting
     * @throws IllegalStateException if already accepting connections from the socket
     */
    default Closeable acceptAll(ServerSocket ss) throws IOException {
        return acceptAll(ss, null);
    }

    /**
     * Accept all server-side connections from the given {@code ServerSocket}. As long as the
     * acceptor is still running, the JVM won't exit.
     *
     * @param listener is called for each accepted socket; return false if socket is rejected
     * @return an object which can be closed to stop accepting
     * @throws IllegalStateException if already accepting connections from the socket
     */
    Closeable acceptAll(ServerSocket ss, Predicate<Socket> listener) throws IOException;

    /**
     * Accept all server-side connections from the given {@code ServerSocketChannel}. As long
     * as the acceptor is still running, the JVM won't exit.
     *
     * @return an object which can be closed to stop accepting
     * @throws IllegalStateException if already accepting connections from the socket channel
     */
    default Closeable acceptAll(ServerSocketChannel ss) throws IOException {
        return acceptAll(ss, null);
    }

    /**
     * Accept all server-side connections from the given {@code ServerSocketChannel}. As long
     * as the acceptor is still running, the JVM won't exit.
     *
     * @param listener is called for each accepted socket; return false if socket is rejected
     * @return an object which can be closed to stop accepting
     * @throws IllegalStateException if already accepting connections from the socket channel
     */
    Closeable acceptAll(ServerSocketChannel ss, Predicate<SocketChannel> listener)
        throws IOException;

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
        return accepted(s.getLocalSocketAddress(), s.getRemoteSocketAddress(),
                        s.getInputStream(), s.getOutputStream());
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
        return accepted(s.getLocalAddress(), s.getRemoteAddress(),
                        Channels.newInputStream(s), Channels.newOutputStream(s));
    }

    /**
     * Call when a server-side connection has been explicitly accepted. Any exception thrown
     * from this method closes the socket.
     *
     * @param localAddr local link address, or null if unknown or not applicable
     * @param remoteAddr remote link address, or null if unknown or not applicable
     * @return new or existing server-side session instance
     * @throws IOException if a communication failure or if the client is requesting an object
     * which isn't exported
     */
    Session<?> accepted(SocketAddress localAddr, SocketAddress remoteAddr,
                        InputStream in, OutputStream out)
        throws IOException;

    /**
     * Call to establish a new client-side session. A remote server must be accepting
     * connections, and it must also export the given named object.
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
     * connections, and it must also export the given named object.
     *
     * @param type the type of the root object which is exported by the remote server
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
     * Assign a connector for establishing new client-side socket connections. By default, the
     * {@link Connector#direct direct} connector is used.
     *
     * @param c non-null connector
     * @return the previously assigned connector
     */
    Connector connector(Connector c) throws IOException;

    /**
     * Provide custom serializers for newly established sessions. If the set of serializers
     * provided by the client and server sessions don't match, then null is serialized for
     * classes which aren't customized on both sides.
     *
     * @throws NullPointerException if any serializers are null
     */
    void customSerializers(Serializer... serializers);

    /**
     * Set the reconnect delay for newly established client sessions (±10%). Client sessions
     * attempt to reconnect when the session is disconnected, and the delay is applied before
     * each attempt. The default reconnect delay is 1 second. Pass a negative delay to disable
     * reconnect, and instead the session is closed when it's disconnected. {@link
     * Session#dispose Disposing} the {@link Session#root root} object also disables reconnect.
     */
    void reconnectDelayMillis(int millis);

    /**
     * Set the ping timeout for newly established sessions (±33%). If no ping response is
     * received from the remote endpoint in time, then the session is disconnected or closed.
     * Server-side sessions are always closed, but client-side sessions are closed only when
     * reconnect is disabled. The default ping timeout is 2 seconds. Pass a negative timeout to
     * disable pings. The session can still close or be disconnected if communication over the
     * control connection fails.
     */
    void pingTimeoutMillis(int millis);

    /**
     * Set the maximum idle connection time for newly established sessions (±33%). This defines
     * the maximum amount of time a connection can remain idle before it's automatically
     * closed. The default idle time is 60 seconds. Pass a negative amount to disable the
     * closing of idle connections.
     */
    void idleConnectionMillis(int millis);

    /**
     * Set the class resolver to use for newly established sessions. This affects the class
     * loading behavior for {@link Serialized} methods.
     *
     * @param resolver resolver to use; pass null to use the default resolver
     */
    void classResolver(ClassResolver resolver);

    /**
     * Convenience method to use a {@code ClassLoader} for resolving classes.
     *
     * @param loader loader to use to resolve classes; pass null to use the default resolver
     */
    default void classLoader(ClassLoader loader) {
        classResolver(loader == null ? null : new ClassLoaderResolver(loader));
    }

    /**
     * Set the handler which is invoked for any uncaught exceptions within this environment
     * instance. The session instance passed to the handler is null when not applicable. By
     * default, uncaught exceptions are passed to the current thread's uncaught exception
     * handler.
     *
     * @param h handler to use; pass null to use the default handler
     * @see Session#uncaughtExceptionHandler
     */
    void uncaughtExceptionHandler(BiConsumer<Session<?>, Throwable> h);

    /**
     * Stops accepting new sessions, closes all acceptors, closes all sessions, and disposes of
     * all exported objects.
     */
    @Override
    void close();
}
