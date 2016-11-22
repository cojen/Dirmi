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
import java.io.InputStream;
import java.io.IOException;

import java.security.SecureRandom;

import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import org.cojen.util.ThrowUnchecked;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.util.Timer;

/**
 * Paired with {@link BasicChannelBrokerConnector} to adapt a ChannelAcceptor
 * into a ChannelBrokerAcceptor.
 *
 * @author Brian S O'Neill
 */
public class BasicChannelBrokerAcceptor implements ChannelBrokerAcceptor {
    static final byte 
        OPEN_REQUEST = 1,
        ACCEPT_REQUEST = 3, // deprecated in favor of ACCEPT_CONFIRM_REQUEST
        CONNECT_REQUEST = 5,
        CONNECT_RESPONSE = 6,
        PING_REQUEST = 7,
        PING_RESPONSE = 8,
        ACCEPT_CONFIRM_REQUEST = 9,
        ACCEPT_SUCCESS_RESPONSE = 10,
        ACCEPT_FAILED_RESPONSE = 11;

    private final IOExecutor mExecutor;
    private final ChannelAcceptor mAcceptor;
    private final SecureRandom mRandom;
    private final ChannelAcceptor.Listener mBrokerListener;

    // Access these fields while synchronized on mAcceptedBrokers.
    private final Map<Long, Broker> mAcceptedBrokers;
    private boolean mClosed;

    private final ListenerQueue<ChannelBrokerAcceptor.Listener> mAcceptListenerQueue;
    private boolean mNotListening;

    public BasicChannelBrokerAcceptor(IOExecutor executor, ChannelAcceptor acceptor) {
        mExecutor = executor;
        mAcceptor = acceptor;
        mRandom = new SecureRandom();
        mAcceptedBrokers = new HashMap<Long, Broker>();

        mAcceptListenerQueue = new ListenerQueue<ChannelBrokerAcceptor.Listener>
            (mExecutor, ChannelBrokerAcceptor.Listener.class);

        mBrokerListener = new ChannelAcceptor.Listener() {
            public void accepted(Channel channel) {
                mAcceptor.accept(this);

                final ChannelBroker broker;
                try {
                    broker = BasicChannelBrokerAcceptor.this.accepted(channel);
                } catch (IOException e) {
                    channel.disconnect();
                    mAcceptListenerQueue.dequeue().failed(e);
                    return;
                }

                if (broker != null) {
                    mAcceptListenerQueue.dequeue().accepted(broker);
                }
            }

            public void rejected(RejectedException e) {
                notListening();
                mAcceptListenerQueue.dequeue().rejected(e);
            }

            public void failed(IOException e) {
                notListening();
                mAcceptListenerQueue.dequeue().failed(e);
            }

            public void closed(IOException e) {
                notListening();
                mAcceptListenerQueue.dequeueForClose().closed(e);
            }
        };

        mAcceptor.accept(mBrokerListener);
    }

    @Override
    public Object getLocalAddress() {
        return mAcceptor.getLocalAddress();
    }

    @Override
    public ChannelBroker accept() throws IOException {
        AcceptListener listener = new AcceptListener();
        accept(listener);
        return listener.waitForBroker();
    }

    @Override
    public ChannelBroker accept(long timeout, TimeUnit unit) throws IOException {
        AcceptListener listener = new AcceptListener();
        accept(listener);
        return listener.waitForBroker(timeout, unit);
    }

    @Override
    public ChannelBroker accept(Timer timer) throws IOException {
        return accept(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    @Override
    public void accept(Listener listener) {
        synchronized (this) {
            if (mNotListening) {
                mNotListening = false;
                try {
                    mAcceptor.accept(mBrokerListener);
                } catch (Throwable e) {
                    mNotListening = true;
                    ThrowUnchecked.fire(e);
                }
            }
        }
        try {
            mAcceptListenerQueue.enqueue(listener);
        } catch (RejectedException e) {
            mAcceptListenerQueue.dequeue().rejected(e);
        }
    }

    synchronized void notListening() {
        mNotListening = true;
    }

    @Override
    public void close() {
        mAcceptor.close();

        {
            Map<Long, Broker> copy;
            synchronized (mAcceptedBrokers) {
                mClosed = true;
                copy = new HashMap<Long, Broker>(mAcceptedBrokers);
                mAcceptedBrokers.clear();
            }

            for (Broker broker : copy.values()) {
                broker.close();
            }
        }
    }

    ChannelBroker accepted(Channel channel) throws IOException {
        ChannelTimeout timeout = new ChannelTimeout(mExecutor, channel, 15, TimeUnit.SECONDS);
        int op;
        long id;
        try {
            InputStream in = channel.getInputStream();
            op = in.read();

            if (op == OPEN_REQUEST) {
                Broker broker;
                synchronized (mAcceptedBrokers) {
                    if (mClosed) {
                        throw new ClosedException("ChannelBrokerAcceptor is closed");
                    }
                    do {
                        id = mRandom.nextLong();
                    } while (mAcceptedBrokers.containsKey(id));
                    broker = new Broker(id, channel);
                    mAcceptedBrokers.put(id, broker);
                }

                try {
                    DataOutputStream dout = new DataOutputStream(channel.getOutputStream());
                    dout.writeLong(id);
                    dout.flush();
                    return broker;
                } catch (IOException e) {
                    broker.close();
                    throw e;
                }
            }

            if (op != ACCEPT_REQUEST && op != CONNECT_RESPONSE && op != ACCEPT_CONFIRM_REQUEST) {
                if (op < 0) {
                    throw new ClosedException("Accepted channel is closed");
                } else {
                    throw new IOException("Invalid operation from accepted channel: " + op);
                }
            }

            id = new DataInputStream(in).readLong();
        } finally {
            timeout.cancel();
        }

        Broker broker;
        synchronized (mAcceptedBrokers) {
            broker = mAcceptedBrokers.get(id);
            if (broker == null && mClosed) {
                throw new ClosedException("ChannelBrokerAcceptor is closed");
            }
        }

        if (broker == null) {
            if (op == CONNECT_RESPONSE) {
                throw new IOException("Reverse connect refers to an unknown session: " + id);
            }
            if (op == ACCEPT_CONFIRM_REQUEST) {
                channel.getOutputStream().write(ACCEPT_FAILED_RESPONSE);
                channel.getOutputStream().flush();
            }
            throw new IOException("Accepted connection refers to an unknown session: " + id);
        }

        if (op == CONNECT_RESPONSE) {
            broker.connected(channel);
        } else if (op == ACCEPT_CONFIRM_REQUEST) {
            channel.getOutputStream().write(ACCEPT_SUCCESS_RESPONSE);
            channel.getOutputStream().flush();
            broker.accepted(channel);
        } else {
            broker.accepted(channel);
        }

        return null;
    }

    void removeBroker(final long id, final Broker broker, boolean immediate) {
        synchronized (mAcceptedBrokers) {
            if (mAcceptedBrokers.get(id) == broker) {
                if (!immediate) {
                    // Keep it around for a bit, to avoid "No broker found"
                    // exceptions resulting from race conditions.
                    try {
                        mExecutor.schedule(new Runnable() {
                            public void run() {
                                removeBroker(id, broker, true /* immediate*/);
                            }
                        }, 10, TimeUnit.SECONDS);
                        return;
                    } catch (RejectedException e) {
                        // Fall through and remove now.
                    }
                }

                mAcceptedBrokers.remove(id);
            }
        }
    }

    private class Broker extends BasicChannelBroker {
        private final ListenerQueue<ChannelConnector.Listener> mListenerQueue;

        Broker(long id, Channel control) throws RejectedException {
            super(mExecutor, id, control);
            mListenerQueue = new ListenerQueue<ChannelConnector.Listener>
                (mExecutor, ChannelConnector.Listener.class);

            mControl.inputNotify(new Channel.Listener() {
                public void ready() {
                    Channel control = mControl;
                    int op;
                    try {
                        op = control.getInputStream().read();
                        if (cPingLogger != null) {
                            logPingMessage("Ping response from " + control + ": " + op);
                        }
                        if (op == PING_RESPONSE) {
                            pinged();
                        } else if (op < 0) {
                            throw new ClosedException("Control channel is closed");
                        } else {
                            throw new IOException
                                    ("Invalid operation from control channel: " + op);
                        }
                        control.inputNotify(this);
                    } catch (IOException e) {
                        closed(e);
                    }
                }

                public void rejected(RejectedException e) {
                    // Safer to close if no threads.
                    closed(e);
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
            return connect(15, TimeUnit.SECONDS);
        }

        @Override
        public Channel connect(long timeout, TimeUnit unit) throws IOException {
            mAllChannels.checkClosed();
            ChannelConnectWaiter listener = new ChannelConnectWaiter();
            connect(listener);
            return listener.waitForChannel(timeout, unit);
        }

        @Override
        public void connect(final ChannelConnector.Listener listener) {
            if (mAllChannels.isClosed()) {
                listener.closed(new ClosedException());
                return;
            }

            try {
                mListenerQueue.enqueue(listener);
            } catch (RejectedException e) {
                dequeueConnectListener().rejected(e);
                return;
            }

            mControl.outputNotify(new Channel.Listener() {
                public void ready() {
                    try {
                        mControl.getOutputStream().write(CONNECT_REQUEST);
                        mControl.flush();
                    } catch (IOException e) {
                        dequeueConnectListener().failed(e);
                        return;
                    }
                }

                public void rejected(RejectedException e) {
                    dequeueConnectListener().rejected(e);
                }

                public void closed(IOException e) {
                    dequeueConnectListenerForClose().closed(e);
                }
            });
        }

        @Override
        protected void close(IOException cause) {
            removeBroker(mId, this, false);
            if (!mAllChannels.isClosed()) {
                dequeueConnectListenerForClose().failed(
                        cause != null ? cause : new ClosedException());
                super.close(cause);
            }
        }

        @Override
        protected boolean requirePingTask() {
            return true;
        }

        @Override
        protected void doPing() throws IOException {
            if (cPingLogger != null) {
                logPingMessage("Ping request to " + mControl);
            }
            try {
                mControl.getOutputStream().write(PING_REQUEST);
                mControl.flush();
            } catch (IOException e) {
                if (cPingLogger != null) {
                    logPingMessage("Ping response failure from " + mControl + ": " + e);
                }
                throw e;
            }
        }

        ChannelConnector.Listener dequeueConnectListener() {
            return mListenerQueue.dequeue();
        }

        ChannelConnector.Listener dequeueConnectListenerForClose() {
            return mListenerQueue.dequeueForClose();
        }

        void connected(Channel channel) {
            channel.register(mAllChannels);
            dequeueConnectListener().connected(channel);
        }
    }

    private static class AcceptListener implements Listener {
        private final Waiter<ChannelBroker> mWaiter = Waiter.create();

        public void accepted(ChannelBroker broker) {
            mWaiter.available(broker);
        }

        public void rejected(RejectedException e) {
            mWaiter.rejected(e);
        }

        public void failed(IOException e) {
            mWaiter.failed(e);
        }

        public void closed(IOException e) {
            mWaiter.closed(e);
        }

        ChannelBroker waitForBroker() throws IOException {
            return mWaiter.waitFor();
        }

        ChannelBroker waitForBroker(long timeout, TimeUnit unit) throws IOException {
            return mWaiter.waitFor(timeout, unit);
        }
    }
}
