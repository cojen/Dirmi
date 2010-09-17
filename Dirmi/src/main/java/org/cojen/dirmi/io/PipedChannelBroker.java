/*
 *  Copyright 2008-2010 Brian S O'Neill
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
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Lock;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.util.Timer;

/**
 * Broker implementation which uses {@link PipedInputStream} and {@link
 * PipedOutputStream}.
 *
 * @author Brian S O'Neill
 */
public class PipedChannelBroker implements ChannelBroker {
    private static final int DEFAULT_BUFFER_SIZE = 100;

    /**
     * Returns a pair of connected brokers.
     */
    public static ChannelBroker[] newPair(IOExecutor executor) {
        return newPair(executor, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Returns a pair of connected brokers.
     */
    public static ChannelBroker[] newPair(IOExecutor executor, int bufferSize) {
        if (bufferSize < 2) {
            // Output needs to have at least 2 bytes to avoid deadlocks caused
            // by object stream resets. Default is larger to provide more
            // buffering to offset the extra overhead.
            bufferSize = 2;
        }

        PipedChannelBroker broker_0 = new PipedChannelBroker(executor, bufferSize);
        PipedChannelBroker broker_1 = new PipedChannelBroker(executor, bufferSize, broker_0);

        return new ChannelBroker[] {broker_0, broker_1};
    }

    private final IOExecutor mExecutor;
    private final int mBufferSize;

    private final CloseableGroup<Channel> mAllChannels;

    volatile PipedChannelBroker mEndpoint;

    private final ListenerQueue<ChannelAcceptor.Listener> mAcceptListenerQueue;

    private PipedChannelBroker(IOExecutor executor, int bufferSize) {
        mExecutor = executor;
        mBufferSize = bufferSize;
        mAllChannels = new CloseableGroup<Channel>();

        mAcceptListenerQueue = new ListenerQueue<ChannelAcceptor.Listener>
            (mExecutor, ChannelAcceptor.Listener.class);
    }

    private PipedChannelBroker(IOExecutor executor, int bufferSize, PipedChannelBroker endpoint) {
        this(executor, bufferSize);
        mEndpoint = endpoint;
        endpoint.mEndpoint = this;
    }

    @Override
    public Object getLocalAddress() {
        return null;
    }

    @Override
    public Object getRemoteAddress() {
        return null;
    }

    @Override
    public Channel connect() throws IOException {
        PipedChannelBroker endpoint = endpoint();

        // cin is for connect side
        PipedInputStream cin = new PipedInputStream();
        // ain is for accept side
        PipedInputStream ain = new PipedInputStream();

        PipedOutputStream aout = new PipedOutputStream(cin);
        PipedOutputStream cout = new PipedOutputStream(ain);

        PipedChannel channel = new PipedChannel(mExecutor, cin, cout, mBufferSize);
        channel.register(mAllChannels);
        endpoint.accepted(ain, aout);

        return channel;
    }

    private void accepted(PipedInputStream ain, PipedOutputStream aout) throws IOException {
        final PipedChannel channel = new PipedChannel
            (mExecutor, ain, aout, mBufferSize);
        channel.register(mAllChannels);

        mExecutor.execute(new Runnable() {
            public void run() {
                mAcceptListenerQueue.dequeue().accepted(channel);
            }
        });
    }

    @Override
    public Channel connect(long timeout, TimeUnit unit) throws IOException {
        return connect();
    }

    @Override
    public Channel connect(Timer timer) throws IOException {
        return connect(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    @Override
    public void connect(final ChannelConnector.Listener listener) {
        try {
            mExecutor.execute(new Runnable() {
                public void run() {
                    Channel channel;
                    try {
                        channel = connect();
                    } catch (IOException e) {
                        listener.failed(e);
                        return;
                    }

                    listener.connected(channel);
                }
            });
        } catch (RejectedException e) {
            listener.rejected(e);
        }
    }

    public Channel accept() throws IOException {
        ChannelAcceptWaiter listener = new ChannelAcceptWaiter();
        accept(listener);
        return listener.waitForChannel();
    }

    @Override
    public Channel accept(long timeout, TimeUnit unit) throws IOException {
        ChannelAcceptWaiter listener = new ChannelAcceptWaiter();
        accept(listener);
        return listener.waitForChannel(timeout, unit);
    }

    @Override
    public Channel accept(Timer timer) throws IOException {
        return accept(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    @Override
    public void accept(ChannelAcceptor.Listener listener) {
        try {
            mAcceptListenerQueue.enqueue(listener);
        } catch (RejectedException e) {
            mAcceptListenerQueue.dequeue().rejected(e);
        }
    }

    @Override
    public void close() {
        PipedChannelBroker endpoint = mEndpoint;
        if (endpoint != null) {
            mEndpoint = null;
            mAllChannels.close();
            endpoint.close();

            // Do last in case it blocks.
            mAcceptListenerQueue.dequeueForClose().closed(new ClosedException());
        }
    }

    private PipedChannelBroker endpoint() throws IOException {
        PipedChannelBroker endpoint = mEndpoint;
        if (endpoint == null) {
            throw new ClosedException();
        }
        return endpoint;
    }
}
