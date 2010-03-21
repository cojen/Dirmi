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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.Remote;

import java.util.Map;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class PipedChannel implements Channel {
    private final IOExecutor mExecutor;
    private final Input mIn;
    private final PipedOutputStream mPout;
    private final BufferedChannelOutputStream mOut;
    private final Map<Channel, Object> mAccepted;

    PipedChannel(IOExecutor executor,
                 PipedInputStream in,
                 PipedOutputStream out,
                 int outputBufferSize,
                 Map<Channel, Object> accepted)
    {
        mExecutor = executor;
        mIn = new Input(in);
        mPout = out;
        mOut = new BufferedChannelOutputStream(this, out, outputBufferSize);
        if (accepted != null) {
            accepted.put(this, "");
        }
        mAccepted = accepted;
    }

    @Override
    public Object getLocalAddress() {
        return mIn.toString();
    }

    @Override
    public Object getRemoteAddress() {
        return mPout.toString();
    }

    @Override
    public InputStream getInputStream() {
        return mIn;
    }

    @Override
    public OutputStream getOutputStream() {
        return mOut;
    }

    @Override
    public boolean isInputReady() throws IOException {
        return mIn.isReady();
    }

    @Override
    public boolean isOutputReady() throws IOException {
        return mPout.isReady() || mOut.isReady();
    }

    @Override
    public int setInputBufferSize(int size) {
        return 0;
    }

    @Override
    public int setOutputBufferSize(int size) {
        return mOut.setBufferSize(size);
    }

    @Override
    public void inputNotify(Channel.Listener listener) {
        mIn.inputNotify(mExecutor, listener);
    }

    @Override
    public void outputNotify(Channel.Listener listener) {
        try {
            if (isOutputReady()) {
                new PipeNotify(mExecutor, listener);
            } else {
                mOut.outputNotify(mExecutor, listener);
            }
        } catch (IOException e) {
            new PipeNotify(mExecutor, listener, e);
        }
    }

    @Override
    public String toString() {
        return "Channel {localAddress=" + getLocalAddress() +
            ", remoteAddress=" + getRemoteAddress() + '}';
    }

    @Override
    public boolean isClosed() {
        return mIn.isClosed() || mPout.isClosed();
    }

    @Override
    public void flush() throws IOException {
        mOut.flush();
    }

    @Override
    public void close() throws IOException {
        if (mAccepted != null) {
            mAccepted.remove(this);
        }
        mOut.outputClose();
        mIn.inputClose();
    }

    @Override
    public void disconnect() {
        if (mAccepted != null) {
            mAccepted.remove(this);
        }
        mOut.outputDisconnect();
        mIn.inputClose();
    }

    @Override
    public Remote installRecycler(Recycler recycler) {
        return null;
    }

    @Override
    public void setRecycleControl(Remote control) {
    }

    private class Input extends InputStream {
        private final PipedInputStream mIn;

        Input(PipedInputStream in) {
            mIn = in;
        }

        @Override
        public int read() throws IOException {
            return mIn.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return mIn.read(b);
        }

        @Override
        public int read(byte[] b, int offset, int length) throws IOException {
            return mIn.read(b, offset, length);
        }

        @Override
        public long skip(long n) throws IOException {
            return mIn.skip(n);
        }

        @Override
        public int available() throws IOException {
            return mIn.available();
        }

        @Override
        public void close() throws IOException {
            PipedChannel.this.close();
        }

        @Override
        public String toString() {
            return mIn.toString();
        }

        void inputNotify(IOExecutor executor, Channel.Listener listener) {
            mIn.inputNotify(executor, listener);
        }

        boolean isReady() throws IOException {
            return mIn.isReady();
        }

        boolean isClosed() {
            return mIn.isClosed();
        }

        void inputClose() {
            mIn.close();
        }
    }
}
