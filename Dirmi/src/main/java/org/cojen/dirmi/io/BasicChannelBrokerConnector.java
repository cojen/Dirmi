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
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.CONNECT_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.CONNECT_RESPONSE;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.PING_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.PING_RESPONSE;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.ACCEPT_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.ACCEPT_CONFIRM_REQUEST;
import static org.cojen.dirmi.io.BasicChannelBrokerAcceptor.ACCEPT_SUCCESS_RESPONSE;

/**
 * Paired with {@link BasicChannelBrokerAcceptor} to adapt a ChannelConnector
 * into a ChannelBrokerConnector.
 *
 * @author Brian S O'Neill
 */
public class BasicChannelBrokerConnector implements ChannelBrokerConnector {
    private final IOExecutor mExecutor;
    private final ChannelConnector mConnector;

    private final CloseableGroup<Broker> mConnectedBrokers;

    public BasicChannelBrokerConnector(IOExecutor executor, ChannelConnector connector) {
        mExecutor = executor;
        mConnector = connector;
        mConnectedBrokers = new CloseableGroup<Broker>();
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
        mConnectedBrokers.checkClosed();
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
                if (mConnectedBrokers.isClosed()) {
                    listener.closed(new ClosedException());
                }

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

            public void closed(IOException e) {
                listener.closed(e);
            }
        });
    }

    @Override
    public void close() {
        mConnectedBrokers.close();
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

    private class Broker extends BasicChannelBroker {
        // 0: unknown, 1: old, 2: new
        private volatile int mProtocol;

        Broker(long id, Channel control) throws RejectedException {
            super(mExecutor, id, control);

            mConnectedBrokers.add(this);

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
                            if (cPingLogger != null) {
                                logPingMessage("Ping request from " + mControl);
                            }
                            control.getOutputStream().write(PING_RESPONSE);
                            control.getOutputStream().flush();
                            if (cPingLogger != null) {
                                logPingMessage("Ping response to " + mControl);
                            }
                            pinged();
                        } catch (IOException e) {
                            if (cPingLogger != null) {
                                logPingMessage("Ping response failure to " + mControl + ": " + e);
                            }
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
                    if (cPingLogger != null) {
                        logPingMessage("Ping check stopping for " + mControl + ": " + e);
                    }
                }

                public void closed(IOException e) {
                    Broker.this.close(e);
                    if (cPingLogger != null) {
                        logPingMessage("Ping check stopping for " + mControl + ": " + e);
                    }
                }
            });
        }

        @Override
        public Channel connect() throws IOException {
            return connect(new Timer(15, TimeUnit.SECONDS));
        }

        @Override
        public Channel connect(long timeout, TimeUnit unit) throws IOException {
            if (timeout < 0) {
                Channel channel = mConnector.connect();
                if (!sendRequest(channel)) {
                    // Retry with old protocol.
                    sendRequest(channel = mConnector.connect());
                }
                return channel;
            } else {
                return connect(new Timer(timeout, unit));
            }
        }

        @Override
        public Channel connect(Timer timer) throws IOException {
            Channel channel = mConnector.connect
                (RemoteTimeoutException.checkRemaining(timer), timer.unit());

            ChannelTimeout timeoutTask =
                new ChannelTimeout(mExecutor, channel,
                                   RemoteTimeoutException.checkRemaining(timer),
                                   timer.unit());

            try {
                if (!sendRequest(channel)) {
                    // Retry with old protocol.
                    channel = mConnector.connect
                        (RemoteTimeoutException.checkRemaining(timer), timer.unit());
                    sendRequest(channel);
                }
                return channel;
            } finally {
                timeoutTask.cancel();
            }
        }

        @Override
        public void connect(final ChannelConnector.Listener listener) {
            mConnector.connect(new ChannelConnector.Listener() {
                public void connected(Channel channel) {
                    try {
                        if (!sendRequest(channel)) {
                            // Retry with old protocol.
                            channel = connect(15, TimeUnit.SECONDS);
                        }
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

                public void closed(IOException e) {
                    listener.closed(e);
                }
            });
        }

        @Override
        protected void close(IOException cause) {
            mConnectedBrokers.remove(this);
            super.close(cause);
        }

        @Override
        protected boolean requirePingTask() {
            // Nothing to do. This side receives pings.
            return false;
        }

        @Override
        protected void doPing() {
            // Should not be called.
            throw new AssertionError();
        }

        /**
         * @return false if channel was closed because only old protocol is supported
         */
        private boolean sendRequest(Channel channel) throws IOException {
            DataOutputStream dout = new DataOutputStream(channel.getOutputStream());

            if (mProtocol == 1) {
                // Always use old deprecated protocol.
                dout.writeByte(ACCEPT_REQUEST);
                dout.writeLong(mId);
                dout.flush();
            } else {
                dout.writeByte(ACCEPT_CONFIRM_REQUEST);
                dout.writeLong(mId);
                dout.flush();

                int response = channel.getInputStream().read();

                if (response < 0) {
                    if (mProtocol == 0) {
                        // Force old protocol.
                        mProtocol = 1;
                        channel.disconnect();
                        return false;
                    }
                    throw new ClosedException("New connection immediately closed");
                }

                // Always use new protocol.
                mProtocol = 2;

                if (response != ACCEPT_SUCCESS_RESPONSE) {
                    channel.disconnect();
                    ClosedException exception = new ClosedException("Stale session");
                    close(exception);
                    throw exception;
                }
            }

            channel.register(mAllChannels);
            return true;
        }
    }
}
