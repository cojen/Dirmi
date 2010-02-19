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
import java.io.OutputStream;

import java.security.SecureRandom;

import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
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
        ACCEPT_REQUEST = 3,
        CONNECT_REQUEST = 5,
        CONNECT_RESPONSE = 6,
        PING_REQUEST = 7,
        PING_RESPONSE = 8;

    private final IOExecutor mExecutor;
    private final ChannelAcceptor mAcceptor;
    private final SecureRandom mRandom;
    private final ChannelAcceptor.Listener mBrokerListener;

    private final ConcurrentHashMap<Long, Broker> mAccepted;

    private final ListenerQueue<ChannelBrokerAcceptor.Listener> mAcceptListenerQueue;
    private boolean mNotListening;

    public BasicChannelBrokerAcceptor(IOExecutor executor, ChannelAcceptor acceptor) {
        mExecutor = executor;
        mAcceptor = acceptor;
        mRandom = new SecureRandom();
        mAccepted = new ConcurrentHashMap<Long, Broker>();

        mAcceptListenerQueue = new ListenerQueue<ChannelBrokerAcceptor.Listener>
            (mExecutor, ChannelBrokerAcceptor.Listener.class);

        mBrokerListener = new ChannelAcceptor.Listener() {
            public void accepted(Channel channel) {
                mAcceptor.accept(this);

                final ChannelBroker broker;
                try {
                    broker = BasicChannelBrokerAcceptor.this.accepted(channel);
                } catch (IOException e) {
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
        for (ChannelBroker broker : mAccepted.values()) {
            broker.close();
        }
    }

    private ChannelBroker accepted(Channel channel) throws IOException {
        try {
            return accepted0(channel);
        } catch (IOException e) {
            channel.disconnect();
            throw e;
        }
    }

    private ChannelBroker accepted0(Channel channel) throws IOException {
        ChannelTimeout timeout = new ChannelTimeout(mExecutor, channel, 15, TimeUnit.SECONDS);
        int op;
        long id;
        try {
            InputStream in = channel.getInputStream();
            op = in.read();

            if (op == OPEN_REQUEST) {
                Broker broker;
                do {
                    id = mRandom.nextLong();
                    broker = new Broker(id, channel);
                } while (mAccepted.putIfAbsent(id, broker) != null);

                try {
                    DataOutputStream dout = new DataOutputStream(channel.getOutputStream());
                    dout.writeLong(id);
                    dout.flush();
                    return broker;
                } catch (IOException e) {
                    mAccepted.remove(id);
                    throw e;
                }
            }

            if (op != ACCEPT_REQUEST && op != CONNECT_RESPONSE) {
                channel.disconnect();
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

        Broker broker = mAccepted.get(id);

        if (broker == null) {
            channel.disconnect();
            throw new IOException("No broker found for id: " + id);
        }

        if (op == CONNECT_RESPONSE) {
            broker.dequeueConnectListener().connected(channel);
        } else {
            broker.accepted(channel);
        }

        return null;
    }

    private class Broker extends BasicChannelBroker {
        private final ListenerQueue<ChannelConnector.Listener> mListenerQueue;

        Broker(long id, Channel control) throws RejectedException {
            super(mExecutor, id, control);
            mListenerQueue = new ListenerQueue<ChannelConnector.Listener>
                (mExecutor, ChannelConnector.Listener.class);
        }

        @Override
        public Channel connect() throws IOException {
            return connect(15, TimeUnit.SECONDS);
        }

        @Override
        public Channel connect(long timeout, TimeUnit unit) throws IOException {
            ChannelConnectWaiter listener = new ChannelConnectWaiter();
            connect(listener);
            return listener.waitForChannel(timeout, unit);
        }

        @Override
        public void connect(final ChannelConnector.Listener listener) {
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
                    dequeueConnectListenerForClose().failed(e);
                }
            });
        }

        @Override
        public void close() {
            if (mAccepted.remove(mId, this)) {
                dequeueConnectListenerForClose().failed(new ClosedException());
                super.close();
            }
        }

        @Override
        protected boolean requirePingTask() {
            return true;
        }

        @Override
        protected boolean doPing() throws IOException {
            mControl.getOutputStream().write(PING_REQUEST);
            mControl.flush();
            return mControl.getInputStream().read() == PING_RESPONSE;
        }

        ChannelConnector.Listener dequeueConnectListener() {
            return mListenerQueue.dequeue();
        }

        ChannelConnector.Listener dequeueConnectListenerForClose() {
            return mListenerQueue.dequeueForClose();
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
