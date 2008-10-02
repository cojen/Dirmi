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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.Socket;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SocketChannel implements StreamChannel {
    private final Socket mSocket;
    private final InputStream mIn;
    private final OutputStream mOut;

    SocketChannel(Socket socket) throws IOException {
        mSocket = socket;
        mIn = new BufferedInputStream(socket.getInputStream());
        mOut = new BufferedOutputStream(socket.getOutputStream());
    }

    public InputStream getInputStream() throws IOException {
        return mIn;
    }

    public OutputStream getOutputStream() throws IOException {
        return mOut;
    }

    public Object getLocalAddress() {
        return mSocket.getLocalSocketAddress();
    }

    public Object getRemoteAddress() {
        return mSocket.getRemoteSocketAddress();
    }

    @Override
    public String toString() {
        return "StreamChannel{localAddress=" + getLocalAddress() +
            ", remoteAddress=" + getRemoteAddress() + '}';
    }

    public void close() throws IOException {
        mSocket.close();
    }

    public boolean isOpen() {
        // FIXME return mSocket.isOpen();
        return true;
    }
}
