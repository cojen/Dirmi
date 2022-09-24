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

import org.cojen.dirmi.core.CoreUtils;

/**
 * Manages a client-side or server-side remote session, which establishes new socket
 * connections as needed. Remote calls over a session are initated from the {@link #root root}
 * object, which can return additional remote objects.
 *
 * @author Brian S O'Neill
 * @see Environment#connect Environment.connect
 * @see SessionAware
 */
public interface Session<R> extends Closeable, Link, Executor {
    /**
     * Access the session that the given remote object is bound to.
     *
     * @throws IllegalArgumentException if not given a remote stub
     * @throws IllegalStateException if the object is disposed
     */
    public static Session<?> access(Object obj) {
        return CoreUtils.accessSession(obj);
    }

    /**
     * Returns the current thread-local session, which is available to a remote method
     * implementation when it's invoked.
     *
     * @throws IllegalStateException if no current session
     */
    public static Session<?> current() {
        return CoreUtils.currentSession();
    }

    /**
     * Returns the root object which was exported or imported.
     */
    R root();

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
     * @throws UnsupportedOperationException if not supported by this session
     */
    void connected(SocketAddress localAddr, SocketAddress remoteAttr,
                   InputStream in, OutputStream out)
        throws IOException;

    /**
     * Set the handler which is invoked for any uncaught exceptions within this session
     * instance. By default, uncaught exceptions are passed to the environment instance.
     *
     * @param h handler to use; pass null to use the default handler
     * @see Environment#uncaughtExceptionHandler
     */
    void uncaughtExceptionHandler(BiConsumer<Session, Throwable> h);

    /**
     * Closes all connections and immediately closes any future connections. All remote objects
     * are invalidated as a side effect.
     */
    @Override
    void close();
}
