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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.util.Random;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.LinkedBlockingQueue;

import org.cojen.util.IntHashMap;

/**
 * Paired with {@link StreamConnectorBroker} to adapt an acceptor into a broker
 * factory.
 *
 * @author Brian S O'Neill
 */
public class StreamBrokerAcceptor implements Closeable {
    final StreamAcceptor mAcceptor;
    final Random mRnd;
    final IntHashMap<Broker> mBrokerMap;
    final LinkedBlockingQueue<Broker> mBrokerAcceptQueue;

    public StreamBrokerAcceptor(StreamAcceptor acceptor) {
        mAcceptor = acceptor;
        mRnd = new Random();
        mBrokerMap = new IntHashMap<Broker>();
        mBrokerAcceptQueue = new LinkedBlockingQueue<Broker>();

        acceptor.accept(new StreamListener() {
            public void established(StreamChannel channel) {
                mAcceptor.accept(this);

                try {
                    int op = channel.getInputStream().read();

                    Broker broker;

                    if (op == StreamConnectorBroker.OP_OPEN) {
                        int id;
                        synchronized (mBrokerMap) {
                            do {
                                id = mRnd.nextInt();
                            } while (mBrokerMap.containsKey(id));
                            // Store null to reserve the id and allow broker
                            // constructor to block.
                            mBrokerMap.put(id, null);
                        }

                        try {
                            broker = new Broker(channel, id);
                        } catch (IOException e) {
                            synchronized (mBrokerMap) {
                                mBrokerMap.remove(id);
                            }
                            throw e;
                        }

                        mBrokerAcceptQueue.add(broker);
                        return;
                    }

                    int id = new DataInputStream(channel.getInputStream()).readInt();

                    synchronized (mBrokerMap) {
                        broker = mBrokerMap.get(id);
                    }

                    if (broker == null) {
                        // Connection is bogus.
                        channel.close();
                        return;
                    }

                    switch (op) {
                    case StreamConnectorBroker.OP_ACCEPT:
                        broker.connected(channel);
                        break;

                    case StreamConnectorBroker.OP_CONNECT:
                        // FIXME: configurable timeout
                        broker.accepted(channel, 10, TimeUnit.SECONDS);
                        break;

                    case StreamConnectorBroker.OP_PING: default:
                        // FIXME
                        break;
                    }
                } catch (IOException e) {
                    // FIXME
                    e.printStackTrace(System.out);
                }
            }

            public void failed(IOException e) {
                // FIXME
                e.printStackTrace(System.out);
            }
        });
    }

    /**
     * Blocks until a new broker is accepted.
     */
    public StreamBroker accept() throws IOException {
        try {
            return mBrokerAcceptQueue.take();
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public void close() throws IOException {
        // FIXME mAcceptor.close();
    }

    private class Broker implements StreamBroker {
        final StreamChannel mControlChannel;
        final DataOutputStream mControlOut;
        final int mId;

        final LinkedBlockingQueue<StreamChannel> mConnectQueue;
        final LinkedBlockingQueue<StreamListener> mAcceptQueue;

        /**
         * @param controlChannel accepted channel
         */
        Broker(StreamChannel controlChannel, int id) throws IOException {
            mControlChannel = controlChannel;
            mId = id;
            mControlOut = new DataOutputStream(controlChannel.getOutputStream());

            mConnectQueue = new LinkedBlockingQueue<StreamChannel>();
            mAcceptQueue = new LinkedBlockingQueue<StreamListener>();

            synchronized (mBrokerMap) {
                mBrokerMap.put(id, this);
            }

            synchronized (mControlOut) {
                mControlOut.writeInt(id);
                mControlOut.flush();
            }
        }

        public void accept(StreamListener listener) {
            mAcceptQueue.add(listener);
        }

        public StreamChannel connect() throws IOException {
            synchronized (mControlOut) {
                mControlOut.write(StreamConnectorBroker.OP_CONNECT);
                mControlOut.writeInt(mId);
                mControlOut.flush();
            }
            try {
                return mConnectQueue.take();
            } catch (InterruptedException e) {
                // FIXME: The queue might get a connection later. Perhaps this
                // broker should be closed?
                throw new InterruptedIOException();
            }
        }

        public void close() throws IOException {
            mControlChannel.close();
        }

        public boolean isOpen() {
            return mControlChannel.isOpen();
        }

        public void execute(Runnable task) {
            mAcceptor.execute(task);
        }

        void connected(StreamChannel channel) {
            mConnectQueue.add(channel);
        }

        void accepted(StreamChannel channel, int timeout, TimeUnit unit) {
            try {
                StreamListener listener = mAcceptQueue.poll(timeout, unit);
                if (listener != null) {
                    listener.established(channel);
                }
                return;
            } catch (InterruptedException e) {
            }

            // Not accepted in time, so close it.
            try {
                channel.close();
            } catch (IOException e) {
                // Ignore.
            }
        }
    }
}
