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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Paired with {@link StreamBrokerAcceptor} to adapt a connector into a full
 * broker.
 *
 * @author Brian S O'Neill
 */
public class StreamConnectorBroker implements StreamBroker {
    static final byte OP_OPEN = 1;
    static final byte OP_PING = 2;
    static final byte OP_ACCEPT = 3;
    static final byte OP_CONNECT = 4;

    final StreamConnector mConnector;
    final StreamChannel mControlChannel;
    final OutputStream mControlOut;
    final DataInputStream mControlIn;
    final int mId;

    final LinkedBlockingQueue<StreamListener> mAcceptQueue;

    public StreamConnectorBroker(StreamConnector connector) throws IOException {
        mConnector = connector;
        mControlChannel = connector.connect();
        mControlOut = mControlChannel.getOutputStream();
        mControlIn = new DataInputStream(mControlChannel.getInputStream());
        mControlOut.write(OP_OPEN);
        mControlOut.flush();
        mId = mControlIn.readInt();

        mAcceptQueue = new LinkedBlockingQueue<StreamListener>();

        mControlChannel.executeWhenReadable(new StreamTask() {
            public void run() {
                try {
                    int command = mControlIn.read();
                    mControlChannel.executeWhenReadable(this);

                    if (command == OP_CONNECT) {
                        final StreamChannel channel = mConnector.connect();
                        DataOutputStream out = new DataOutputStream(channel.getOutputStream());
                        out.write(OP_ACCEPT);
                        out.writeInt(mId);
                        out.flush();

                        try {
                            // FIXME: configurable timeout
                            StreamListener listener = mAcceptQueue.poll(10, TimeUnit.SECONDS);
                            if (listener != null) {
                                listener.established(channel);
                                return;
                            }
                        } catch (InterruptedException e) {
                        }

                        // Not accepted in time, so close it.
                        try {
                            channel.close();
                        } catch (IOException e) {
                            // Ignore.
                        }
                    }
                } catch (IOException e) {
                    closed(e);
                }
            }

            public void closed() {
                System.out.println("Closed");
                // FIXME
            }

            public void closed(IOException e) {
                System.out.println("Closed: ");
                e.printStackTrace(System.out);
                // FIXME
            }
        });
    }

    public void accept(StreamListener listener) {
        mAcceptQueue.add(listener);
    }

    public StreamChannel connect() throws IOException {
        StreamChannel channel = mConnector.connect();
        DataOutputStream out = new DataOutputStream(channel.getOutputStream());
        out.write(OP_CONNECT);
        out.writeInt(mId);
        out.flush();
        return channel;
    }

    public void close() throws IOException {
        mControlChannel.close();
    }

    public boolean isOpen() {
        return mControlChannel.isOpen();
    }

    public void execute(Runnable task) {
        mConnector.execute(task);
    }
}
