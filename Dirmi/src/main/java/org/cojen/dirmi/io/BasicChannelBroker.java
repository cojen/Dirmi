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

import java.io.IOException;

import java.lang.ref.WeakReference;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.util.logging.Logger;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;

import org.cojen.dirmi.util.ScheduledTask;
import org.cojen.dirmi.util.Timer;

/**
 * Abstract Broker used by BasicChannelBrokerAcceptor and BasicChannelBrokerConnector.
 *
 * @author Brian S O'Neill
 */
abstract class BasicChannelBroker implements ChannelBroker {
    // How often to perform ping task.
    private static final int PING_DELAY_MILLIS = 2500;

    // How often to check if pings are being received.
    private static final int PING_CHECK_DELAY_MILLIS = 1000;

    // No ping responses after this threshold causes broker to close.
    private static final int PING_FAILURE_MILLIS = 10000 - PING_CHECK_DELAY_MILLIS;

    static final Logger cPingLogger;

    static {
        String prop = System.getProperty(BasicChannelBroker.class.getName() + ".LOG_PINGS");
        if (prop == null || !prop.equalsIgnoreCase("true")) {
            cPingLogger = null;
        } else {
            cPingLogger = Logger.getLogger(BasicChannelBroker.class.getName());
        }
    }

    protected final long mId;
    protected final Channel mControl;

    protected final CloseableGroup<Channel> mAllChannels;
    private final ListenerQueue<ChannelAcceptor.Listener> mListenerQueue;

    private final Future<?> mScheduledPingCheck;
    private final Future<?> mScheduledDoPing;
    private volatile long mLastPingNanos;

    BasicChannelBroker(IOExecutor executor, long id, Channel control)
        throws RejectedException
    {
        mId = id;
        mControl = control;
        mAllChannels = new CloseableGroup<Channel>();

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
                mScheduledPingCheck.cancel(false);
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
            ", remoteAddress=" + getRemoteAddress() + '}';
    }

    protected void accepted(Channel channel) {
        channel.register(mAllChannels);
        mListenerQueue.dequeue().accepted(channel);
    }

    protected void close(IOException cause) {
        if (mAllChannels.isClosed()) {
            return;
        }

        try {
            if (cause == null) {
                cause = new ClosedException();
            }

            if (mScheduledPingCheck != null) {
                mScheduledPingCheck.cancel(false);
            }

            if (mScheduledDoPing != null) {
                mScheduledDoPing.cancel(false);
            }

            mControl.disconnect();

            // Disconnect will not block trying to flush output or recycle
            // input. There's no point in attempting to recycle channels anyhow,
            // although unflushed output will get lost. Brokers are only used by
            // Sessions, which aren't required to flush when closed. User must
            // explicitly flush the Session for that behavior.
            mAllChannels.disconnect();
        } finally {
            // Do last in case it blocks.
            mListenerQueue.dequeueForClose().closed(cause);
        }
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

    protected abstract void doPing() throws IOException;

    void logPingMessage(String message) {
        if (cPingLogger != null) {
            cPingLogger.info(message);
        }
    }

    private boolean pingCheck() {
        long lag = System.nanoTime() - mLastPingNanos;
        if (lag > PING_FAILURE_MILLIS * 1000000L) {
            if (cPingLogger != null) {
                logPingMessage("Ping missed for " + mControl + ": " + (lag / 1000000L) + " > " + 
                               PING_FAILURE_MILLIS);
            }
            close(new ClosedException("Ping failure"));
            return false;
        } else {
            if (cPingLogger != null &&
                lag >= (PING_DELAY_MILLIS + PING_CHECK_DELAY_MILLIS) * 1000000L)
            {
                logPingMessage("Ping lag for " + mControl + ": " + (lag / 1000000L) + " <= " + 
                               PING_FAILURE_MILLIS);
            }
            return true;
        }
    }

    private static abstract class PingTask extends ScheduledTask<RuntimeException> {
        private final WeakReference<BasicChannelBroker> mBrokerRef;

        private volatile Future<?> mScheduled;

        PingTask(BasicChannelBroker broker) {
            mBrokerRef = new WeakReference<BasicChannelBroker>(broker);
        }

        protected void doRun() {
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
            broker.doPing();
            return true;
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
