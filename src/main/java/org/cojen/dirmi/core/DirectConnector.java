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

package org.cojen.dirmi.core;

import org.cojen.dirmi.Connector;
import org.cojen.dirmi.Session;

import java.io.IOException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import java.nio.channels.SocketChannel;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class DirectConnector implements Connector {
    public static final DirectConnector THE = new DirectConnector();

    @Override
    public void connect(Session session, SocketAddress address) throws IOException {
        if (address instanceof InetSocketAddress) {
            /*
              Favor the Socket class, due to a 20 year old SocketChannel design flaw which
              prevents concurrent reads and writes. It's finally fixed in Java 19.

              https://bugs.openjdk.org/browse/JDK-8279339

              If the given address isn't supported, then the SocketChannel must be used, but it
              won't work correctly for anything older than Java 19. The only other known
              address type is UnixDomainSocketAddress, added in Java 16.
            */
            var s = new Socket();
            s.connect(address);
            session.connected(s);
        } else {
            session.connected(SocketChannel.open(address));
        }
    }
}
