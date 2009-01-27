/*
 *  Copyright 2009 Brian S O'Neill
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

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker which counts all bytes sent and received by all channels.
 *
 * @author Brian S O'Neill
 */
public class CountingBroker implements Broker<StreamChannel> {
    private final Broker<StreamChannel> mBroker;

    private final AtomicLong mRead = new AtomicLong();
    private final AtomicLong mWritten = new AtomicLong();

    public CountingBroker(Broker<StreamChannel> broker) {
        mBroker = broker;
    }

    public StreamChannel connect() throws IOException {
        return new CountingChannel(mBroker.connect(), mRead, mWritten);
    }

    public void accept(final AcceptListener<StreamChannel> listener) {
        mBroker.accept(new AcceptListener<StreamChannel>() {
            public void established(StreamChannel channel) {
                try {
                    listener.established(new CountingChannel(channel, mRead, mWritten));
                } catch (IOException e) {
                    failed(e);
                }
            }

            public void failed(IOException e) {
                listener.failed(e);
            }
        });
    }

    public Object getLocalAddress() {
        return mBroker.getLocalAddress();
    }

    public Object getRemoteAddress() {
        return mBroker.getRemoteAddress();
    }

    public void close() throws IOException {
        mBroker.close();
    }

    public long getBytesRead() {
        return mRead.get();
    }

    public long getBytesWritten() {
        return mWritten.get();
    }

    private static class CountingChannel implements StreamChannel {
        private final StreamChannel mChannel;
        private final InputStream mIn;
        private final OutputStream mOut;

        CountingChannel(StreamChannel channel, AtomicLong read, AtomicLong written)
            throws IOException
        {
            mChannel = channel;
            mIn = new CountingInput(mChannel.getInputStream(), read);
            mOut = new CountingOutput(mChannel.getOutputStream(), written);
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        public boolean isOpen() {
            return mChannel.isOpen();
        }

        public void remoteClose() throws IOException {
            mChannel.remoteClose();
        }

        public Object getLocalAddress() {
            return mChannel.getLocalAddress();
        }

        public Object getRemoteAddress() {
            return mChannel.getRemoteAddress();
        }

        public void disconnect() {
            mChannel.disconnect();
        }

        public Closeable getCloser() {
            return mChannel.getCloser();
        }

        public void addCloseListener(CloseListener listener) {
            mChannel.addCloseListener(listener);
        }

        public void close() throws IOException {
            mChannel.close();
        }
    }

    private static class CountingInput extends FilterInputStream {
        private final AtomicLong mCounter;

        CountingInput(InputStream in, AtomicLong counter) {
            super(in);
            mCounter = counter;
        }

        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) {
                mCounter.getAndIncrement();
            }
            return c;
        }

        public int read(byte[] b, int offset, int length) throws IOException {
            int amt = super.read(b, offset, length);
            if (amt > 0) {
                mCounter.getAndAdd(amt);
            }
            return amt;
        }

        public long skip(long n) throws IOException {
            long amt = super.skip(n);
            if (amt > 0) {
                mCounter.getAndAdd(amt);
            }
            return amt;
        }
    }

    private static class CountingOutput extends FilterOutputStream {
        private final AtomicLong mCounter;

        CountingOutput(OutputStream out, AtomicLong counter) {
            super(out);
            mCounter = counter;
        }

        public void write(int b) throws IOException {
            super.write(b);
            mCounter.getAndIncrement();
        }

        public void write(byte[] b, int offset, int length) throws IOException {
            super.write(b, offset, length);
            mCounter.getAndAdd(length);
        }
    }
}
