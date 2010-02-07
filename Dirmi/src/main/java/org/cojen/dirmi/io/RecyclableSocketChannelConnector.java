/*
 *  Copyright 2010 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.IOException;

import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/**
 * Implements a connectot using TCP/IP which supports channel recycling. A
 * recycler must be installed on connected channels, and remote endpoint must
 * be using {@link RecyclableSocketChannelAcceptor}.
 *
 * @author Brian S O'Neill
 */
public class RecyclableSocketChannelConnector extends SocketChannelConnector {
    /**
     * @param remoteAddress address to connect to
     */
    public RecyclableSocketChannelConnector(IOExecutor executor, SocketAddress remoteAddress) {
        super(executor, remoteAddress);
    }

    /**
     * @param remoteAddress address to connect to
     * @param localAddress local address to bind to; pass null for any
     */
    public RecyclableSocketChannelConnector(IOExecutor executor,
                                            SocketAddress remoteAddress,
                                            SocketAddress localAddress)
    {
        super(executor, remoteAddress, localAddress);
    }

    /**
     * @param remoteAddress address to connect to
     * @param localAddress local address to bind to; pass null for any
     */
    public RecyclableSocketChannelConnector(IOExecutor executor,
                                            SocketAddress remoteAddress,
                                            SocketAddress localAddress,
                                            SocketFactory factory)
    {
        super(executor, remoteAddress, localAddress, factory);
    }

    @Override
    Channel createChannel(Socket socket) throws IOException {
        return new RecyclableSocketChannel(executor(), socket, null);
    }
}
