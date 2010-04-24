/*
 *  Copyright 2010 Brian S O'Neill
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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.net.SocketAddress;
import java.net.SocketException;

import java.nio.ByteBuffer;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import java.rmi.Remote;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;

import org.cojen.dirmi.util.Timer;

/**
 * Factory for TCP channel acceptors and connectors that use selectable
 * sockets.
 *
 * @author Brian S O'Neill
 */
public class RecyclableSocketChannelSelector implements SocketChannelSelector {
    private final IOExecutor mExecutor;
    private final Selector mSelector;
    private final ConcurrentLinkedQueue<Selectable> mQueue;

    public RecyclableSocketChannelSelector(IOExecutor executor) throws IOException {
        this(executor, Selector.open());
    }

    private RecyclableSocketChannelSelector(IOExecutor executor, Selector selector) {
        if (executor == null || selector == null) {
            throw new IllegalArgumentException();
        }
        mExecutor = executor;
        mSelector = selector;
        mQueue = new ConcurrentLinkedQueue<Selectable>();
    }

    /**
     * Perform socket selection, returning normally only when selector is closed.
     */
    public void selectLoop() throws IOException {
        IOExecutor executor = mExecutor;
        Selector selector = mSelector;
        ConcurrentLinkedQueue<Selectable> queue = mQueue;

        try {
            while (true) {
                int count = selector.select();

                Selectable selectable;
                while ((selectable = queue.poll()) != null) {
                    selectable.register(selector);
                }

                if (count == 0) {
                    if (!selector.isOpen()) {
                        return;
                    }
                } else {
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        Selectable selected = (Selectable) key.attachment();
                        try {
                            try {
                                executor.execute(selected);
                            } catch (RejectedException e) {
                                try {
                                    executor.schedule(selected, 0, TimeUnit.SECONDS);
                                } catch (RejectedException e2) {
                                    selected.rejected(e);
                                }
                            }
                        } finally {
                            key.cancel();
                        }
                    }
                }
            }
        } catch (ClosedSelectorException e) {
            // Ignore and return.
        }
    }

    public void close() throws IOException {
        mSelector.close();
    }

    public ChannelAcceptor newChannelAcceptor(SocketAddress localAddress) throws IOException {
        return new NioChannelAcceptor(localAddress);
    }

    public ChannelConnector newChannelConnector(SocketAddress remoteAddress) {
        return newChannelConnector(remoteAddress, null);
    }

    public ChannelConnector newChannelConnector(SocketAddress remoteAddress,
                                                SocketAddress localAddress)
    {
        return new NioChannelConnector(remoteAddress, localAddress);
    }

    /**
     * Register a listener which is asynchronously notified when channel has
     * been connected. Listener is called at most once per registration.
     */
    void connectNotify(SocketChannel channel, ChannelConnector.Listener listener) {
        mQueue.add(new ConnectNotify(channel, listener));
        mSelector.wakeup();
    }

    /**
     * Register a listener which is asynchronously notified when channel has
     * been accepted. Listener is called at most once per registration.
     */
    void acceptNotify(AccessControlContext context, Map<Channel, Object> accepted,
                      ServerSocketChannel channel, ChannelAcceptor.Listener listener)
    {
        mQueue.add(new AcceptNotify(context, accepted, channel, listener));
        mSelector.wakeup();
    }

    /**
     * Register a listener which is asynchronously notified when channel can be
     * read from. Listener is called at most once per registration.
     */
    public void inputNotify(SocketChannel channel, Channel.Listener listener) {
        mQueue.add(new ChannelNotify(channel, listener, SelectionKey.OP_READ));
        mSelector.wakeup();
    }

    /**
     * Register a listener which is asynchronously notified when channel can be
     * written to. Listener is called at most once per registration.
     */
    public void outputNotify(SocketChannel channel, Channel.Listener listener) {
        mQueue.add(new ChannelNotify(channel, listener, SelectionKey.OP_WRITE));
        mSelector.wakeup();
    }

    public IOExecutor executor() {
        return mExecutor;
    }

    private static abstract class Selectable implements Runnable {
        abstract void register(Selector selector);

        abstract void rejected(RejectedException cause);
    }

    private static class ChannelNotify extends Selectable {
        private final SocketChannel mChannel;
        private final Channel.Listener mListener;
        private final int mOps;

        ChannelNotify(SocketChannel channel, Channel.Listener listener, int ops) {
            mChannel = channel;
            mListener = listener;
            mOps = ops;
        }

        void register(Selector selector) {
            try {
                mChannel.register(selector, mOps, this);
            } catch (ClosedChannelException e) {
                mListener.closed(e);
            } catch (RuntimeException e) {
                try {
                    mChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                mListener.closed(new IOException(e));
            }
        }

        void rejected(RejectedException cause) {
            mListener.rejected(cause);
        }

        public void run() {
            mListener.ready();
        }
    }

    private class ConnectNotify extends Selectable {
        private final SocketChannel mChannel;
        private final ChannelConnector.Listener mListener;

        ConnectNotify(SocketChannel channel, ChannelConnector.Listener listener) {
            mChannel = channel;
            mListener = listener;
        }

        void register(Selector selector) {
            try {
                mChannel.register(selector, SelectionKey.OP_CONNECT, this);
            } catch (ClosedChannelException e) {
                mListener.failed(e);
            } catch (RuntimeException e) {
                try {
                    mChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                mListener.failed(new IOException(e));
            }
        }

        void rejected(RejectedException cause) {
            mListener.rejected(cause);
        }

        public void run() {
            try {
                mChannel.finishConnect();
                NioSocketChannel nsc =
                    new NioSocketChannel(RecyclableSocketChannelSelector.this, mChannel);
                mListener.connected(new NioRecyclableSocketChannel(executor(), nsc, null));
            } catch (IOException e) {
                mListener.failed(e);
            }
        }
    }

    private class AcceptNotify extends Selectable {
        private final AccessControlContext mContext;
        private final Map<Channel, Object> mAccepted;
        final ServerSocketChannel mChannel;
        private final ChannelAcceptor.Listener mListener;

        AcceptNotify(AccessControlContext context, Map<Channel, Object> accepted,
                     ServerSocketChannel channel, ChannelAcceptor.Listener listener)
        {
            mContext = context;
            mAccepted = accepted;
            mChannel = channel;
            mListener = listener;
        }

        void register(Selector selector) {
            try {
                mChannel.register(selector, SelectionKey.OP_ACCEPT, this);
            } catch (ClosedChannelException e) {
                mListener.closed(e);
            } catch (RuntimeException e) {
                try {
                    mChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                mListener.closed(new IOException(e));
            }
        }

        void rejected(RejectedException cause) {
            mListener.rejected(cause);
        }

        public void run() {
            SocketChannel channel;
            try {
                try {
                    channel = AccessController
                        .doPrivileged(new PrivilegedExceptionAction<SocketChannel>() {
                            public SocketChannel run() throws IOException {
                                return mChannel.accept();
                            }
                        }, mContext);
                    channel.configureBlocking(false);
                } catch (PrivilegedActionException e) {
                    throw (IOException) e.getCause();
                }
            } catch (SecurityException e) {
                mListener.failed(new IOException(e));
                return;
            } catch (Exception e) {
                try {
                    mChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                mListener.closed(e instanceof IOException ? (IOException) e : new IOException(e));
                return;
            }

            try {
                NioSocketChannel nsc =
                    new NioSocketChannel(RecyclableSocketChannelSelector.this, channel);
                mListener.accepted(new NioRecyclableSocketChannel(executor(), nsc, mAccepted));
            } catch (IOException e) {
                mListener.failed(e);
            }
        }
    }

    static Timer toTimer(long timeout, TimeUnit unit) {
        Timer timer;
        if (timeout < 0) {
            return null;
        } else if (timeout == 0) {
            return new Timer(0, TimeUnit.NANOSECONDS);
        } else {
            return new Timer(timeout, unit);
        }
    }

    private class NioChannelAcceptor implements ChannelAcceptor {
        private final SocketAddress mLocalAddress;
        private final ServerSocketChannel mChannel;
        private final AccessControlContext mContext;

        private final Map<Channel, Object> mAccepted;
        final ConcurrentLinkedQueue<Channel> mAcceptQueue;

        volatile boolean mClosed;

        NioChannelAcceptor(SocketAddress localAddress) throws IOException {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.socket().setReuseAddress(true);
            ssc.socket().bind(localAddress);

            mLocalAddress = ssc.socket().getLocalSocketAddress();
            mChannel = ssc;
            mContext = AccessController.getContext();
            mAccepted = new ConcurrentHashMap<Channel, Object>();
            mAcceptQueue = new ConcurrentLinkedQueue<Channel>();
        }

        @Override
        public Channel accept() throws IOException {
            return accept(-1, null);
        }

        @Override
        public Channel accept(long timeout, TimeUnit unit) throws IOException {
            return accept(toTimer(timeout, unit));
        }

        @Override
        public Channel accept(Timer timer) throws IOException {
            if (mClosed) {
                throw new ClosedException();
            }

            class Listener implements ChannelAcceptor.Listener {
                private Channel mChannel;
                private IOException mException;
                private boolean mAbandoned;

                @Override
                public synchronized void accepted(Channel channel) {
                    if (mAbandoned) {
                        mAcceptQueue.add(channel);
                    } else {
                        mChannel = channel;
                        notify();
                    }
                }

                @Override
                public synchronized void rejected(RejectedException cause) {
                    mException = cause;
                    notify();
                }

                @Override
                public synchronized void failed(IOException cause) {
                    mException = cause;
                    notify();
                }

                @Override
                public synchronized void closed(IOException cause) {
                    mException = cause;
                    notify();
                }

                synchronized Channel waitForChannel(Timer timer) throws IOException {
                    while (true) {
                        if (mChannel != null) {
                            return mChannel;
                        }
                        if (mException != null) {
                            if (mException.getCause() instanceof SecurityException) {
                                throw (SecurityException) mException.getCause();
                            }
                            throw mException;
                        }
                        try {
                            try {
                                if (timer == null) {
                                    wait();
                                } else {
                                    long remaining = RemoteTimeoutException.checkRemaining(timer);
                                    wait(timer.unit().toMillis(remaining));
                                } 
                            } catch (InterruptedException e) {
                                throw new InterruptedIOException();
                            }
                        } catch (IOException e) {
                            mAbandoned = true;
                            throw e;
                        }
                    }
                }
            };

            Listener listener = new Listener();
            accept(listener);

            return listener.waitForChannel(timer);
        }

        @Override
        public void accept(final Listener listener) {
            Channel channel = mAcceptQueue.poll();
            if (channel != null) {
                // FIXME: race conditions cause channel to be lost
                // FIXME: separate thread for listener call
                listener.accepted(channel);
            }

            acceptNotify(mContext, mAccepted, mChannel, new ChannelAcceptor.Listener() {
                @Override
                public void accepted(Channel channel) {
                    if (acceptedChannel(channel)) {
                        listener.accepted(channel);
                    } else {
                        listener.closed(new ClosedException());
                    }
                }

                @Override
                public void rejected(RejectedException cause) {
                    listener.rejected(cause);
                }

                @Override
                public void failed(IOException cause) {
                    listener.failed(cause);
                }

                @Override
                public void closed(IOException cause) {
                    listener.closed(cause);
                }
            });
        }

        @Override
        public void close() {
            mClosed = true;

            try {
                mChannel.close();
            } catch (IOException e) {
                // Ignore.
            }

            for (Channel channel : mAccepted.keySet()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    // Ignore.
                }
            }

            mAccepted.clear();
        }

        @Override
        public Object getLocalAddress() {
            return mLocalAddress;
        }

        @Override
        public String toString() {
            return "ChannelAcceptor {localAddress=" + mLocalAddress + '}';
        }

        boolean acceptedChannel(Channel channel) {
            if (mClosed) {
                channel.disconnect();
                return false;
            }
            return true;
        }
    }

    private class NioChannelConnector implements ChannelConnector {
        final SocketAddress mRemoteAddress;
        final SocketAddress mLocalAddress;
        private final AccessControlContext mContext;

        NioChannelConnector(SocketAddress remoteAddress, SocketAddress localAddress) {
            if (remoteAddress == null) {
                throw new IllegalArgumentException("Must provide a remote address");
            }
            mRemoteAddress = remoteAddress;
            mLocalAddress = localAddress;
            mContext = AccessController.getContext();
        }

        @Override
        public Object getRemoteAddress() {
            return mRemoteAddress;
        }

        @Override
        public Object getLocalAddress() {
            return mLocalAddress;
        }

        @Override
        public Channel connect() throws IOException {
            return connect(-1, null);
        }

        @Override
        public Channel connect(long timeout, TimeUnit unit) throws IOException {
            ChannelConnectWaiter waiter = new ChannelConnectWaiter();
            connect(waiter);
            if (timeout < 0) {
                return waiter.waitForChannel();
            } else {
                return waiter.waitForChannel(timeout, unit);
            }
        }

        @Override
        public Channel connect(Timer timer) throws IOException {
            return connect(timer.duration(), timer.unit());
        }

        @Override
        public void connect(Listener listener) {
            SocketChannel sc;
            try {
                try {
                    sc = AccessController
                        .doPrivileged(new PrivilegedExceptionAction<SocketChannel>() {
                            public SocketChannel run() throws IOException {
                                SocketChannel sc = SocketChannel.open();
                                sc.configureBlocking(false);
                                if (mLocalAddress != null) {
                                    sc.socket().bind(mLocalAddress);
                                }
                                sc.connect(mRemoteAddress);
                                return sc;
                            }
                        }, mContext);
                } catch (PrivilegedActionException e) {
                    throw (IOException) e.getCause();
                }
            } catch (IOException e) {
                listener.failed(e);
                return;
            }

            connectNotify(sc, listener);
        }

        @Override
        public String toString() {
            return "ChannelConnector {localAddress=" + mLocalAddress +
                ", remoteAddress=" + mRemoteAddress + '}';
        }
    }
}
