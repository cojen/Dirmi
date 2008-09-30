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

package dirmi.nio2;

import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.util.concurrent.Executor;

/**
 * Implementation which uses blocking I/O.
 *
 * @author Brian S O'Neill
 */
public class SocketStreamProcessor2 implements Closeable {
    final Executor mExecutor;

    public SocketStreamProcessor2(Executor executor) throws IOException {
        if (executor == null) {
            throw new IllegalArgumentException();
        }
        mExecutor = executor;
    }

    public StreamConnector newConnector(SocketAddress endpoint) {
        return newConnector(endpoint, null);
    }

    public StreamConnector newConnector(final SocketAddress endpoint,
                                        final SocketAddress bindpoint)
    {
        if (endpoint == null) {
            throw new IllegalArgumentException();
        }

        return new StreamConnector() {
            public StreamChannel connect() throws IOException {
                Socket socket = new Socket();
                if (bindpoint != null) {
                    socket.bind(bindpoint);
                }
                socket.connect(endpoint);
                socket.setTcpNoDelay(true);
                return new Chan(socket);
            }

            public void execute(Runnable task) {
                mExecutor.execute(task);
            }

            @Override
            public String toString() {
                return "StreamConnector {endpoint=" + endpoint + ", bindpoint=" + bindpoint + '}';
            }
        };
    }

    public StreamAcceptor newAcceptor(final SocketAddress bindpoint) throws IOException {
        if (bindpoint == null) {
            throw new IllegalArgumentException();
        }

        final ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(bindpoint);

        class Accept implements Runnable {
            private final StreamListener mListener;

            Accept(StreamListener listener) {
                mListener = listener;
            }

            public void run() {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    mListener.established(new Chan(socket));
                } catch (IOException e) {
                    mListener.failed(e);
                }
            }
        };

        return new StreamAcceptor() {
            public void accept(StreamListener listener) {
                execute(new Accept(listener));
            }

            // FIXME: remove
            public void execute(Runnable task) {
                mExecutor.execute(task);
            }

            @Override
            public String toString() {
                return "StreamAcceptor {bindpoint=" + bindpoint + '}';
            }
        };
    }

    public void close() throws IOException {
        // FIXME
    }

    private class Chan implements StreamChannel {
        private final Socket mSocket;
        private final Input mIn;
        private final Output mOut;

        Chan(Socket socket) throws IOException {
            mSocket = socket;
            mIn = new Input(socket.getInputStream());
            mOut = new Output(socket.getOutputStream());
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        // FIXME: remove
        public void executeWhenReadable(StreamTask task) throws IOException {
            execute(task);
        }

        public Object getLocalAddress() {
            return mSocket.getLocalSocketAddress();
        }

        public Object getRemoteAddress() {
            return mSocket.getRemoteSocketAddress();
        }

        @Override
        public String toString() {
            return "StreamChannel {localAddress=" + getLocalAddress() +
                ", remoteAddress=" + getRemoteAddress() + '}';
        }

        public void execute(Runnable task) {
            mExecutor.execute(task);
        }

        public void close() throws IOException {
            mSocket.close();
        }

        public boolean isOpen() {
            // FIXME return mSocket.isOpen();
            return true;
        }
    }

    private static class Input extends AbstractBufferedInputStream {
        private final InputStream mIn;

        Input(InputStream in) {
            mIn = in;
        }

        @Override
        public synchronized int available() throws IOException {
            int available = super.available();
            if (available > 0) {
                return available;
            }
            return mIn.available();
        }

        @Override
        protected int doRead(byte[] buffer, int offset, int length) throws IOException {
            return mIn.read(buffer, offset, length);
        }

        @Override
        protected long doSkip(long n) throws IOException {
            return mIn.skip(n);
        }
    }

    private static class Output extends AbstractBufferedOutputStream {
        private final OutputStream mOut;

        Output(OutputStream out) {
            mOut = out;
        }

        @Override
        public synchronized void flush() throws IOException {
            super.flush();
            mOut.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            super.flush();
            mOut.close();
        }

        @Override
        protected void doWrite(byte[] buffer, int offset, int length) throws IOException {
            mOut.write(buffer, offset, length);
        }
    }
}
