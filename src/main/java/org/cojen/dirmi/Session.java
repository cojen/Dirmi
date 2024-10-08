/*
 *  Copyright 2006-2022 Cojen.org
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

import java.net.Socket;
import java.net.SocketAddress;

import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;

import java.util.concurrent.Executor;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import org.cojen.dirmi.core.CoreUtils;

/**
 * Manages a client-side or server-side remote session, which establishes new socket
 * connections as needed. Remote calls over a session are initiated from the {@link #root root}
 * object, which can return additional remote objects.
 *
 * @author Brian S O'Neill
 * @see Environment#connect Environment.connect
 * @see SessionAware
 */
public interface Session<R> extends Closeable, Link, Executor {
    /**
     * Returns the root object which was exported or imported.
     */
    R root();

    /**
     * Access the session that the given remote object is bound to. This is method is expected
     * to be called on the client-side.
     *
     * @throws IllegalArgumentException if not given a remote stub
     * @throws IllegalStateException if the object is disposed
     */
    static Session<?> access(Object obj) {
        return CoreUtils.accessSession(obj);
    }

    /**
     * Returns the current thread-local session, which is available to a remote method
     * implementation when it's invoked. This method is expected to be called on the server-side.
     *
     * @throws IllegalStateException if no current session
     */
    static Session<?> current() {
        return CoreUtils.currentSession();
    }

    /**
     * Explicitly dispose a client-side remote object.
     *
     * @throws IllegalArgumentException if not given a remote stub
     * @return false if the object is already disposed
     * @see Disposer
     */
    static boolean dispose(Object obj) {
        return CoreUtils.dispose(obj);
    }

    /**
     * Explicitly dispose a server-side remote object implementation from the current session.
     *
     * @throws IllegalStateException if no current session
     * @return false if the server is already disposed or if it's unknown to the current session
     * @see Disposer
     */
    static boolean disposeServer(Object server) {
        return CoreUtils.disposeServer(server);
    }

    /**
     * Receives new connections from a {@link Connector Connector}.
     *
     * @throws UnsupportedOperationException if not supported by this session
     */
    default void connected(Socket s) throws IOException {
        CoreUtils.setOptions(s);
        connected(s.getLocalSocketAddress(), s.getRemoteSocketAddress(),
                  s.getInputStream(), s.getOutputStream());
    }

    /**
     * Receives new connections from a {@link Connector Connector}.
     *
     * @throws UnsupportedOperationException if not supported by this session
     */
    default void connected(SocketChannel s) throws IOException {
        CoreUtils.setOptions(s);
        connected(s.getLocalAddress(), s.getRemoteAddress(),
                  Channels.newInputStream(s), Channels.newOutputStream(s));
    }

    /**
     * Receives new connections from a {@link Connector Connector}.
     *
     * @param localAddr local link address, or null if unknown or not applicable
     * @param remoteAddr remote link address, or null if unknown or not applicable
     * @throws UnsupportedOperationException if not supported by this session
     */
    void connected(SocketAddress localAddr, SocketAddress remoteAddr,
                   InputStream in, OutputStream out)
        throws IOException;

    /**
     * Resolve a class using the default or custom {@link ClassResolver}.
     */
    Class<?> resolveClass(String name) throws IOException, ClassNotFoundException;

    /**
     * Set the handler which is invoked for any uncaught exceptions within this session
     * instance. By default, uncaught exceptions are passed to the environment instance.
     *
     * @param h handler to use; pass null to use the default handler
     * @see Environment#uncaughtExceptionHandler
     */
    void uncaughtExceptionHandler(BiConsumer<Session<?>, Throwable> h);

    /**
     * Pass an uncaught exception directly to the uncaught exception handler.
     */
    void uncaught(Throwable e);

    /**
     * Returns the current session state.
     */
    State state();

    /**
     * Add a listener which is immediately invoked (in the current thread), or when the session
     * state changes, or when a reconnect attempt fails. The listener is invoked in a blocking
     * fashion, preventing any state changes until the listener returns. If it returns false,
     * the listener is removed.
     */
    void addStateListener(BiPredicate<Session<?>, Throwable> listener);

    /**
     * Closes all connections and initiates a reconnect. Operation has no effect if the session
     * is closed. If this is a server-side session, calling reconnect is effectively the same
     * as calling close. The client-side session detects this and performs the reconnect.
     */
    void reconnect();

    /**
     * Closes all connections and immediately closes any future connections. All remote objects
     * are invalidated as a side effect.
     */
    @Override
    void close();

    /**
     * Indicates the session's current connection state.
     *
     * @see Session#state
     */
    public enum State {
        /**
         * Indicates that the session is currently connected, and remote calls are likely to
         * succeed.
         */
        CONNECTED,

        /**
         * Indicates that the session isn't connected, and it's about to begin reconnecting.
         */
        DISCONNECTED,

        /**
         * Indicates that the session is attempting to reconnect, and all remote calls from
         * this session are failing.
         */
        RECONNECTING,

        /**
         * Indicates that the session has just finished reconnecting, but remote calls will
         * still fail until the session state is connected.
         */
        RECONNECTED,

        /**
         * Indicates that the session is permanently closed.
         */
        CLOSED;
    }
}
