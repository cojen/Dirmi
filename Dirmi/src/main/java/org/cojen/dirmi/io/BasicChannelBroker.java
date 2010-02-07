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

import java.lang.ref.WeakReference;

import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicBoolean;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.util.Timer;

/**
 * Abstract Broker used by BasicChannelBrokerAcceptor and BasicChannelBrokerConnector.
 *
 * @author Brian S O'Neill
 */
abstract class BasicChannelBroker implements ChannelBroker {
    // How often to peform ping.
    private static final int PING_DELAY_MILLIS = 5000;

    // How often to check if pings are being received.
    private static final int PING_CHECK_DELAY_MILLIS = 1000;

    // No ping responses after this threshold causes broker to close.
    private static final int PING_FAILURE_MILLIS = PING_DELAY_MILLIS * 2 - PING_CHECK_DELAY_MILLIS;

    protected final long mId;
    protected final Channel mControl;

    private final AtomicBoolean mClosed;
    private final Map<Accepted, Object> mAccepted;
    private final ListenerQueue<ChannelAcceptor.Listener> mListenerQueue;

    private final Future<?> mScheduledPingCheck;
    private final Future<?> mScheduledDoPing;
    private volatile long mLastPingNanos;

    BasicChannelBroker(IOExecutor executor, long id, Channel control)
        throws RejectedException
    {
        mId = id;
        mControl = control;
        mClosed = new AtomicBoolean(false);
        mAccepted = new ConcurrentHashMap<Accepted, Object>();

        mListenerQueue = new ListenerQueue<ChannelAcceptor.Listener>
            (executor, ChannelAcceptor.Listener.class);

        // Buffers only need to be large enough for command and broker id.
        control.setInputBufferSize(10);
        control.setOutputBufferSize(10);

        mLastPingNanos = System.nanoTime();

        // Use separate tasks for checking and performing pings. If one task
        // was used, a hanging doPing prevents checks.

        PingTask pinger = new PingCheckTask(this);
        try {
            mScheduledPingCheck = executor.scheduleWithFixedDelay
                (pinger, PING_CHECK_DELAY_MILLIS, PING_CHECK_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedException e) {
            control.disconnect();
            throw e;
        }
        pinger.scheduled(mScheduledPingCheck);

        if (!requirePingTask()) {
            mScheduledDoPing = null;
        } else {
            pinger = new DoPingTask(this);
            try {
                mScheduledDoPing = executor.scheduleWithFixedDelay
                    (pinger, PING_DELAY_MILLIS, PING_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedException e) {
                mScheduledPingCheck.cancel(true);
                control.disconnect();
                throw e;
            }
            pinger.scheduled(mScheduledDoPing);
        }
    }

    @Override
    public Object getRemoteAddress() {
        return mControl.getRemoteAddress();
    }

    @Override
    public Object getLocalAddress() {
        return mControl.getLocalAddress();
    }

    @Override
    public Channel connect(Timer timer) throws IOException {
        return connect(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    @Override
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
            mListenerQueue.enqueue(listener);
        } catch (RejectedException e) {
            mListenerQueue.dequeue().rejected(e);
        }
    }

    @Override
    public void close() {
        close(null);
    }

    @Override
    public String toString() {
        return "ChannelBroker {localAddress=" + getLocalAddress() +
            ", remoteAddress=" + getRemoteAddress();
    }

    protected void accepted(Channel channel) {
        mListenerQueue.dequeue().accepted(new Accepted(channel));
    }

    protected void close(IOException cause) {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        if (cause == null) {
            cause = new ClosedException();
        }

        if (mScheduledPingCheck != null) {
            mScheduledPingCheck.cancel(true);
        }

        if (mScheduledDoPing != null) {
            mScheduledDoPing.cancel(true);
        }

        mControl.disconnect();

        for (Channel channel : mAccepted.keySet()) {
            channel.disconnect();
        }

        // Do last in case it blocks.
        mListenerQueue.dequeueForClose().closed(cause);
    }

    /**
     * Call when ping received.
     */
    protected void pinged() {
        mLastPingNanos = System.nanoTime();
    }

    /**
     * @return true if doPing should be called periodically
     */
    protected abstract boolean requirePingTask();

    protected abstract boolean doPing() throws IOException;

    private boolean pingCheck() {
        if ((System.nanoTime() - mLastPingNanos) > PING_FAILURE_MILLIS * 1000000L) {
            close(new ClosedException("Ping failure"));
            return false;
        } else {
            return true;
        }
    }

    private class Accepted implements Channel {
        private final Channel mChannel;

        Accepted(Channel channel) {
            mChannel = channel;
            mAccepted.put(this, "");
        }

        @Override
        public Object getLocalAddress() {
            return mChannel.getLocalAddress();
        }

        @Override
        public Object getRemoteAddress() {
            return mChannel.getRemoteAddress();
        }

        @Override
        public InputStream getInputStream(){
            return mChannel.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            return mChannel.getOutputStream();
        }

        @Override
        public boolean isInputReady() throws IOException {
            return mChannel.isInputReady();
        }

        @Override
        public boolean isOutputReady() throws IOException {
            return mChannel.isOutputReady();
        }

        @Override
        public int setInputBufferSize(int size) {
            return mChannel.setInputBufferSize(size);
        }

        @Override
        public int setOutputBufferSize(int size) {
            return mChannel.setOutputBufferSize(size);
        }

        @Override
        public void inputNotify(Channel.Listener listener) {
            mChannel.inputNotify(new UnregisterListener(listener));
        }

        @Override
        public void outputNotify(Channel.Listener listener) {
            mChannel.outputNotify(new UnregisterListener(listener));
        }

        private class UnregisterListener implements Channel.Listener {
            private final Channel.Listener mListener;

            UnregisterListener(Channel.Listener listener) {
                mListener = listener;
            }

            public void ready() {
                mListener.ready();
            }

            public void rejected(RejectedException e) {
                mListener.rejected(e);
            }

            public void closed(IOException e) {
                unregister();
                mListener.closed(e);
            }
        }

        @Override
        public void flush() throws IOException {
            mChannel.flush();
        }

        @Override
        public boolean isClosed() {
            return mChannel.isClosed();
        }

        @Override
        public void close() throws IOException {
            unregister();
            mChannel.close();
        }

        @Override
        public void disconnect() {
            unregister();
            mChannel.disconnect();
        }

        @Override
        public Object installRecycler(Recycler recycler) {
            return mChannel.installRecycler(recycler);
        }

        @Override
        public void setRecycleControl(Object control) {
            mChannel.setRecycleControl(control);
        }

        @Override
        public String toString() {
            return mChannel.toString();
        }

        void unregister() {
            mAccepted.remove(this);
        }
    }

    private static abstract class PingTask implements Runnable {
        private final WeakReference<BasicChannelBroker> mBrokerRef;

        private volatile Future<?> mScheduled;

        PingTask(BasicChannelBroker broker) {
            mBrokerRef = new WeakReference<BasicChannelBroker>(broker);
        }

        public void run() {
            BasicChannelBroker broker = mBrokerRef.get();
            if (broker != null) {
                try {
                    if (doTask(broker)) {
                        return;
                    }
                    broker.close(new ClosedException("Ping failure"));
                } catch (IOException e) {
                    broker.close(new ClosedException("Ping failure", e));
                }
            }

            // Cancel ourself. Not expected to be null, so just do it.
            mScheduled.cancel(true);
        }

        void scheduled(Future<?> scheduled) {
            mScheduled = scheduled;
        }

        /**
         * @return false if failed
         */
        abstract boolean doTask(BasicChannelBroker broker) throws IOException;
    }

    private static class DoPingTask extends PingTask {
        DoPingTask(BasicChannelBroker broker) {
            super(broker);
        }

        @Override
        boolean doTask(BasicChannelBroker broker) throws IOException {
            if (broker.doPing()) {
                broker.pinged();
                return true;
            } else {
                return false;
            }
        }
    }

    private static class PingCheckTask extends PingTask {
        PingCheckTask(BasicChannelBroker broker) {
            super(broker);
        }

        @Override
        boolean doTask(BasicChannelBroker broker) {
            return broker.pingCheck();
        }
    }
}
