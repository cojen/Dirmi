/*
 *  Copyright 2007 Brian S O'Neill
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

package dirmi.io;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.Socket;
import java.net.SocketAddress;

import java.util.concurrent.TimeUnit;

/**
 * Basic connection implementation that wraps a socket.
 *
 * @author Brian S O'Neill
 */
public class SocketConnection implements Connection {
    private final Socket mSocket;

    public SocketConnection(Socket socket) {
        mSocket = socket;
    }

    public InputStream getInputStream() throws IOException {
        return mSocket.getInputStream();
    }

    public long getReadTimeout() throws IOException {
        int timeout = mSocket.getSoTimeout();
        if (timeout == 0) {
            timeout = -1;
        }
        return timeout;
    }

    public TimeUnit getReadTimeoutUnit() throws IOException {
        return TimeUnit.MILLISECONDS;
    }

    public void setReadTimeout(long time, TimeUnit unit) throws IOException {
        long millis;
        if (time <= 0) {
            millis = time < 0 ? 0 : 1;
        } else {
            millis = unit.toMillis(time);
            if (millis > Integer.MAX_VALUE) {
                millis = Integer.MAX_VALUE;
            }
        }
        mSocket.setSoTimeout((int) millis);
    }

    public OutputStream getOutputStream() throws IOException {
        return mSocket.getOutputStream();
    }

    public long getWriteTimeout() throws IOException {
        // FIXME: may need to use Selector
        return -1;
    }

    public TimeUnit getWriteTimeoutUnit() throws IOException {
        return TimeUnit.MILLISECONDS;
    }

    public void setWriteTimeout(long time, TimeUnit unit) throws IOException {
        // FIXME: may need to use Selector
    }

    public String getLocalAddressString() {
        SocketAddress addr = mSocket.getLocalSocketAddress();
        return addr == null ? null : addr.toString();
    }

    public String getRemoteAddressString() {
        SocketAddress addr = mSocket.getRemoteSocketAddress();
        return addr == null ? null : addr.toString();
    }

    public void close() throws IOException {
        mSocket.close();
    }
}

