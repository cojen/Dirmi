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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.util.Timer;

import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.OPEN_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.ACCEPT_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.CONNECT_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.CONNECT_RESPONSE;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.PING_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.PING_RESPONSE;

/**
 * Paired with {@link BasicChannelBrokerAcceptor} to adapt a ChannelConnector
 * into a ChannelBrokerConnector.
 *
 * @author Brian S O'Neill
 */
public class BasicChannelBrokerConnector implements ChannelBrokerConnector {
    private final IOExecutor mExecutor;
    private final ChannelConnector mConnector;

    public BasicChannelBrokerConnector(IOExecutor executor, ChannelConnector connector) {
        mExecutor = executor;
        mConnector = connector;
    }

    @Override
    public Object getRemoteAddress() {
        return mConnector.getRemoteAddress();
    }

    @Override
    public Object getLocalAddress() {
        return mConnector.getLocalAddress();
    }

    @Override
    public ChannelBroker connect() throws IOException {
        return connected(mConnector.connect(), null);
    }

    @Override
    public ChannelBroker connect(long timeout, TimeUnit unit) throws IOException {
        return timeout < 0 ? connect() : connect(new Timer(timeout, unit));
    }

    @Override
    public ChannelBroker connect(Timer timer) throws IOException {
        return connected(mConnector.connect(timer), timer);
    }

    @Override
    public void connect(final Listener listener) {
        mConnector.connect(new ChannelConnector.Listener() {
            public void connected(Channel channel) {
                ChannelBroker broker;
                try {
                    broker = BasicChannelBrokerConnector.this.connected(channel, null);
                } catch (IOException e) {
                    listener.failed(e);
                    return;
                }

                listener.connected(broker);
            }

            public void rejected(RejectedException e) {
                listener.rejected(e);
            }

            public void failed(IOException e) {
                listener.failed(e);
            }
        });
    }

    private ChannelBroker connected(Channel channel, Timer timer) throws IOException {
        if (timer == null) {
            timer = new Timer(15, TimeUnit.SECONDS);
        }
        try {
            long id;
            ChannelTimeout timeout = new ChannelTimeout(mExecutor, channel, timer);
            try {
                channel.getOutputStream().write(OPEN_REQUEST);
                channel.flush();
                id = new DataInputStream(channel.getInputStream()).readLong();
            } finally {
                timeout.cancel();
            }
            return new Broker(id, channel);
        } catch (IOException e) {
            channel.disconnect();
            throw e;
        }
    }

    static final int MAX_PING_FAILURES = 2;

    private class Broker extends BasicChannelBroker {
        Broker(long id, Channel control) throws RejectedException {
            super(mExecutor, id, control);

            mControl.inputNotify(new Channel.Listener() {
                public void ready() {
                    Channel control = mControl;
                    int op;
                    try {
                        op = control.getInputStream().read();
                        if (op != PING_REQUEST && op != CONNECT_REQUEST) {
                            if (op < 0) {
                                throw new ClosedException("Control channel is closed");
                            } else {
                                throw new IOException
                                    ("Invalid operation from control channel: " + op);
                            }
                        }
                        control.inputNotify(this);
                    } catch (IOException e) {
                        closed(e);
                        return;
                    }

                    if (op == PING_REQUEST) {
                        try {
                            control.getOutputStream().write(PING_RESPONSE);
                            control.getOutputStream().flush();
                            pinged();
                        } catch (IOException e) {
                            close(new ClosedException("Ping failure", e));
                        }
                        return;
                    }

                    Channel channel;
                    try {
                        channel = mConnector.connect();
                        DataOutputStream dout = new DataOutputStream(channel.getOutputStream());
                        dout.writeByte(CONNECT_RESPONSE);
                        dout.writeLong(mId);
                        dout.flush();
                    } catch (IOException e) {
                        closed(e);
                        return;
                    }

                    accepted(channel);
                }

                public void rejected(RejectedException e) {
                    // Safer to close if no threads.
                    // FIXME: Reconsider this choice, but always close if shutdown.
                    closed(e);
                }

                public void closed(IOException e) {
                    Broker.this.close(e);
                }
            });
        }

        @Override
        public Channel connect() throws IOException {
            return sendRequest(mConnector.connect());
        }

        @Override
        public Channel connect(long timeout, TimeUnit unit) throws IOException {
            return sendRequest(mConnector.connect(timeout, unit));
        }

        @Override
        public void connect(final ChannelConnector.Listener listener) {
            mConnector.connect(new ChannelConnector.Listener() {
                public void connected(Channel channel) {
                    try {
                        channel = sendRequest(channel);
                    } catch (IOException e) {
                        listener.failed(e);
                        return;
                    }

                    listener.connected(channel);
                }

                public void rejected(RejectedException e) {
                    listener.rejected(e);
                }

                public void failed(IOException e) {
                    listener.failed(e);
                }
            });
        }

        @Override
        protected boolean requirePingTask() {
            // Nothing to do. This side receives pings.
            return false;
        }

        @Override
        protected boolean doPing() {
            // Should not be called.
            throw new AssertionError();
        }

        private Channel sendRequest(Channel channel) throws IOException {
            DataOutputStream dout = new DataOutputStream(channel.getOutputStream());
            dout.writeByte(ACCEPT_REQUEST);
            dout.writeLong(mId);
            dout.flush();
            return channel;
        }
    }
}
