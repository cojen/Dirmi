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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import java.nio.ByteBuffer;

import java.nio.channels.SocketChannel;

import java.net.SocketException;

import org.cojen.dirmi.RejectedException;

/**
 *
 * @author Brian S O'Neill
 */
class NioSocketChannel implements SimpleSocket {
    final SocketChannelSelector mSelector;
    final SocketChannel mChannel;
    private final Input mIn;
    private final Output mOut;

    NioSocketChannel(SocketChannelSelector selector, SocketChannel channel) {
        try {
            channel.socket().setTcpNoDelay(true);
        } catch (SocketException e) {
            // Ignore.
        }
        mSelector = selector;
        mChannel = channel;
        mIn = new Input();
        mOut = new Output();
    }

    public void flush() throws IOException {
        mOut.flush();
    }

    public void close() throws IOException {
        try {
            mChannel.close();
        } finally {
            mIn.ready();
            mOut.ready();
        }
    }

    public Object getLocalAddress() {
        return mChannel.socket().getLocalSocketAddress();
    }

    public Object getRemoteAddress() {
        return mChannel.socket().getRemoteSocketAddress();
    }

    public InputStream getInputStream() {
        return mIn;
    }

    public OutputStream getOutputStream() {
        return mOut;
    }

    public void inputNotify(Channel.Listener listener) {
        mSelector.inputNotify(mChannel, listener);
    }

    public void outputNotify(final Channel.Listener listener) {
        mSelector.outputNotify(mChannel, listener);
    }

    // Class is intended to be wrapped to provide buffering and thread-safety.
    private class Input extends InputStream implements Channel.Listener {
        private ByteBuffer mBuffer;
        private byte[] mWrapped;

        private IOException mNotify;

        @Override
        public int read() throws IOException {
            // Reads from PacketInputStream are always buffered, and so this
            // code doesn't really need to be optimized.
            byte[] b = new byte[1];
            return read(b, 0, 1) <= 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ByteBuffer buffer;
            if (mWrapped == b) {
                buffer = mBuffer;
            } else {
                mBuffer = buffer = ByteBuffer.wrap(b);
                mWrapped = b;
            }

            buffer.position(off).limit(off + len);
            SocketChannel channel = mChannel;

            int amt;
            while ((amt = channel.read(buffer)) == 0) {
                mSelector.inputNotify(channel, this);
                synchronized (this) {
                    IOException ex;
                    while ((ex = mNotify) == null) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            throw new InterruptedIOException();
                        }
                    }
                    mNotify = null;
                    if (ex != Ready.THE) {
                        ex.fillInStackTrace();
                        throw ex;
                    }
                }
            }

            return amt;
        }

        @Override
        public void close() throws IOException {
            NioSocketChannel.this.close();
        }

        @Override
        public synchronized void ready() {
            if (mNotify == null) {
                mNotify = Ready.THE;
            }
            notifyAll();
        }

        @Override
        public void rejected(RejectedException cause) {
            try {
                mChannel.close();
            } catch (IOException e) {
            }
            closed(cause);
        }

        @Override
        public synchronized void closed(IOException cause) {
            mNotify = cause;
            notifyAll();
        }
    }

    // Class is intended to be wrapped to provide buffering and thread-safety.
    private class Output extends OutputStream implements Channel.Listener {
        private ByteBuffer mBuffer;
        private byte[] mWrapped;

        private IOException mNotify;

        @Override
        public void write(int b) throws IOException {
            // Writes from PacketOutputStream are usually buffered, and so this
            // code doesn't really need to be optimized.
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ByteBuffer buffer;
            if (mWrapped == b) {
                buffer = mBuffer;
            } else {
                mBuffer = buffer = ByteBuffer.wrap(b);
                mWrapped = b;
            }

            buffer.position(off).limit(off + len);
            SocketChannel channel = mChannel;

            int amt;
            while ((amt = channel.write(buffer)) < len) {
                len -= amt;
                mSelector.outputNotify(channel, this);
                synchronized (this) {
                    IOException ex;
                    while ((ex = mNotify) == null) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            throw new InterruptedIOException();
                        }
                    }
                    mNotify = null;
                    if (ex != Ready.THE) {
                        ex.fillInStackTrace();
                        throw ex;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            NioSocketChannel.this.close();
        }

        @Override
        public synchronized void ready() {
            if (mNotify == null) {
                mNotify = Ready.THE;
            }
            notifyAll();
        }

        @Override
        public void rejected(RejectedException cause) {
            try {
                mChannel.close();
            } catch (IOException e) {
            }
            closed(cause);
        }

        @Override
        public synchronized void closed(IOException cause) {
            mNotify = cause;
            notifyAll();
        }
    }

    private static class Ready extends IOException {
        static final Ready THE = new Ready();

        private Ready() {
        }

        // Override to remove the stack trace.
        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
