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

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.util.Map;

/**
 * Implements an acceptor using TCP/IP which supports channel recycling. A
 * recycler must be installed on accepted channels, and remote endpoint must be
 * using {@link RecyclableSocketChannelConnector}.
 *
 * @author Brian S O'Neill
 */
public class RecyclableSocketChannelAcceptor extends SocketChannelAcceptor {
    public RecyclableSocketChannelAcceptor(IOExecutor executor, SocketAddress localAddress)
        throws IOException
    {
        super(executor, localAddress);
    }

    public RecyclableSocketChannelAcceptor(IOExecutor executor,
                                           SocketAddress localAddress,
                                           ServerSocket serverSocket)
        throws IOException
    {
        super(executor, localAddress, serverSocket);
    }

    @Override
    Channel createChannel(Socket socket, Map<Channel, Object> accepted)
        throws IOException
    {
        return new RecyclableSocketChannel(executor(), socket, accepted);
    }
}
