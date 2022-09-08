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

import java.net.Socket;

import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;

import org.cojen.dirmi.core.CoreUtils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface Session<R> extends Closeable {
    /**
     * Returns the root object which was exported or imported.
     */
    R root();

    /**
     * Receives new connections from a {@link Connector Connector}.
     */
    default void connected(Socket s) throws IOException {
        CoreUtils.setOptions(s);
        connected(s.getInputStream(), s.getOutputStream());
    }

    /**
     * Receives new connections from a {@link Connector Connector}.
     */
    default void connected(SocketChannel s) throws IOException {
        CoreUtils.setOptions(s);
        connected(Channels.newInputStream(s), Channels.newOutputStream(s));
    }

    /**
     * Receives new connections from a {@link Connector Connector}.
     */
    void connected(InputStream in, OutputStream out) throws IOException;

    /**
     * Closes all connections but allows new connections to be established. All non-restorable
     * remote objects are invalidated as a side effect.
     */
    void reset();

    /**
     * Closes all connections and immediately closes any future connections. All remote objects
     * are invalidated as a side effect.
     */
    @Override
    void close();
}
