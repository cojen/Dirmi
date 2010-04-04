/*
 *  Copyright 2009-2010 Brian S O'Neill
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

import java.rmi.Remote;

import java.util.concurrent.atomic.AtomicLong;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.util.Timer;

/**
 * Broker which counts all bytes sent and received by all channels.
 *
 * @author Brian S O'Neill
 */
public class CountingBroker implements ChannelBroker {
    private final ChannelBroker mBroker;

    private final AtomicLong mRead = new AtomicLong();
    private final AtomicLong mWritten = new AtomicLong();

    public CountingBroker(ChannelBroker broker) {
        mBroker = broker;
    }

    public Channel connect() throws IOException {
        return new CountingChannel(mBroker.connect(), mRead, mWritten);
    }

    public Channel connect(long timeout, TimeUnit unit) throws IOException {
        return new CountingChannel(mBroker.connect(timeout, unit), mRead, mWritten);
    }

    public Channel connect(Timer timer) throws IOException {
        return connect(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    public void connect(final ChannelConnector.Listener listener) {
        mBroker.connect(new ChannelConnector.Listener() {
            public void connected(Channel channel) {
                listener.connected(new CountingChannel(channel, mRead, mWritten));
            }

            public void rejected(RejectedException e) {
                listener.rejected(e);
            }

            public void failed(IOException e) {
                listener.failed(e);
            }
        });
    }

    public Channel accept() throws IOException {
        return new CountingChannel(mBroker.accept(), mRead, mWritten);
    }

    public Channel accept(long timeout, TimeUnit unit) throws IOException {
        return new CountingChannel(mBroker.accept(timeout, unit), mRead, mWritten);
    }

    public Channel accept(Timer timer) throws IOException {
        return accept(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    public void accept(final ChannelAcceptor.Listener listener) {
        mBroker.accept(new ChannelAcceptor.Listener() {
            public void accepted(Channel channel) {
                listener.accepted(new CountingChannel(channel, mRead, mWritten));
            }

            public void rejected(RejectedException e) {
                listener.rejected(e);
            }

            public void closed(IOException e) {
                listener.closed(e);
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

    public void close() {
        mBroker.close();
    }

    public long getBytesRead() {
        return mRead.get();
    }

    public long getBytesWritten() {
        return mWritten.get();
    }

    private static class CountingChannel implements Channel {
        private final Channel mChannel;
        private final InputStream mIn;
        private final OutputStream mOut;

        CountingChannel(Channel channel, AtomicLong read, AtomicLong written) {
            mChannel = channel;
            mIn = new CountingInput(mChannel.getInputStream(), read);
            mOut = new CountingOutput(mChannel.getOutputStream(), written);
        }

        public InputStream getInputStream() {
            return mIn;
        }

        public OutputStream getOutputStream() {
            return mOut;
        }

        public Object getLocalAddress() {
            return mChannel.getLocalAddress();
        }

        public Object getRemoteAddress() {
            return mChannel.getRemoteAddress();
        }

        public boolean isInputReady() throws IOException {
            return mChannel.isInputReady();
        }

        public boolean isOutputReady() throws IOException {
            return mChannel.isOutputReady();
        }

        public int setInputBufferSize(int size) {
            return mChannel.setInputBufferSize(size);
        }

        public int setOutputBufferSize(int size) {
            return mChannel.setOutputBufferSize(size);
        }

        public void inputNotify(Listener listener) {
            mChannel.inputNotify(listener);
        }

        public void outputNotify(Listener listener) {
            mChannel.outputNotify(listener);
        }

        public boolean inputResume() {
            return mChannel.inputResume();
        }

        public boolean isResumeSupported() {
            return mChannel.isResumeSupported();
        }

        public boolean outputSuspend() throws IOException {
            return mChannel.outputSuspend();
        }

        public void flush() throws IOException {
            mChannel.flush();
        }

        public boolean isClosed() {
            return mChannel.isClosed();
        }

        public void close() throws IOException {
            mChannel.close();
        }

        public void disconnect() {
            mChannel.disconnect();
        }

        public Remote installRecycler(Recycler recycler) {
            return mChannel.installRecycler(recycler);
        }

        public void setRecycleControl(Remote control) {
            mChannel.setRecycleControl(control);
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
