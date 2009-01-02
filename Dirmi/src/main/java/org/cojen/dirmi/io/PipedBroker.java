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

package org.cojen.dirmi.io;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import java.util.concurrent.locks.Lock;

/**
 * Broker implementation which uses {@link PipedInputStream} and {@link
 * PipedOutputStream}.
 *
 * @author Brian S O'Neill
 */
public class PipedBroker extends AbstractStreamBroker implements Broker<StreamChannel> {
    private PipedBroker mEndpoint;

    /**
     * Creates an unconnected broker.
     */
    public PipedBroker(Executor executor) {
        super(executor);
    }

    /**
     * Creates a connected broker.
     */
    public PipedBroker(Executor executor, PipedBroker endpoint) throws IOException {
        super(executor);
        Lock lock = closeLock();
        try {
            Lock endpointLock = endpoint.closeLock();
            try {
                mEndpoint = endpoint;
                endpoint.mEndpoint = this;
            } finally {
                endpointLock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }

    public StreamChannel connect() throws IOException {
        int channelId = reserveChannelId();
        StreamChannel channel = null;
        try {
            PipedInputStream pin = new PipedInputStream();
            PipedOutputStream pout = new PipedOutputStream();
            mEndpoint.accept(pin, pout);
            channel = new Channel(pin, pout);
            Lock lock = closeLock();
            try {
                register(channelId, channel);
                return channel;
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            unregisterAndDisconnect(channelId, channel);
            try {
                close();
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        }
    }

    private void accept(PipedInputStream pin, PipedOutputStream pout) throws IOException {
        final int channelId = reserveChannelId();
        StreamChannel channel = null;
        try {
            PipedInputStream myPin = new PipedInputStream(pout);
            PipedOutputStream myPout = new PipedOutputStream(pin);
            channel = new Channel(myPin, myPout);
            final StreamChannel fchannel = channel;
            Lock lock = closeLock();
            try {
                try {
                    mExecutor.execute(new Runnable() {
                        public void run() {
                            accepted(channelId, fchannel);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    IOException ioe = new IOException(e.getMessage());
                    ioe.initCause(e);
                    throw ioe;
                }
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            unregisterAndDisconnect(channelId, channel);
            try {
                close();
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        }
    }

    @Override
    void preClose() {
    }

    @Override
    void closeControlChannel() {
    }

    private class Channel implements StreamChannel {
        private final InputStream mIn;
        private final OutputStream mOut;

        Channel(InputStream in, OutputStream out) {
            mIn = in;
            mOut = out;
        }

        public Object getLocalAddress() {
            return PipedBroker.this.toString();
        }

        public Object getRemoteAddress() {
            return PipedBroker.this.mEndpoint.toString();
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        public void disconnect() {
            try {
                close();
            } catch (IOException e) {
            }
        }

        public void close() throws IOException {
            mIn.close();
            mOut.close();
        }
    }
}
