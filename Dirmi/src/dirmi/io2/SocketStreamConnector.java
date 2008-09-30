/*
 *  Copyright 2008 Brian S O'Neill
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

package dirmi.io2;

import java.io.IOException;

import java.net.Socket;
import java.net.SocketAddress;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SocketStreamConnector implements StreamConnector {
    private final SocketAddress mEndpoint;
    private final SocketAddress mBindpoint;

    public SocketStreamConnector(SocketAddress endpoint) {
        this(endpoint, null);
    }

    public SocketStreamConnector(SocketAddress endpoint, SocketAddress bindpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException();
        }
        mEndpoint = endpoint;
        mBindpoint = bindpoint;
    }

    public StreamChannel connect() throws IOException {
        Socket socket = new Socket();
        if (mBindpoint != null) {
            socket.bind(mBindpoint);
        }
        socket.connect(mEndpoint);
        socket.setTcpNoDelay(true);
        return new SocketChannel(socket);
    }

    @Override
    public String toString() {
        return "StreamConnector {endpoint=" + mEndpoint + ", bindpoint=" + mBindpoint + '}';
    }
}
