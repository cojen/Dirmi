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

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.nio.ByteBuffer;

import java.nio.channels.ClosedChannelException;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.security.SecureRandom;

import org.cojen.util.IntHashMap;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class MultiplexedStreamBroker implements StreamBroker {
    private static final long MAGIC_NUMBER = 0x1dca09fe04baafbeL;

    static final int HEADER_SIZE = 4;

    // Each command is encoded with 4 byte header. Upper 2 bits encode the
    // opcode, and remaining 30 bits encode the channel id.

    static final int SEND  = 0; // all bits must be clear
    static final int ACK   = 1;
    static final int CLOSE = 3; // all bits must be set

    final MessageChannel mMessChannel;

    final IntHashMap<Chan> mChannels;
    final ReadWriteLock mChannelsLock;

    final BufferPool mBufferPool;

    final BlockingQueue<StreamListener> mListeners;

    final int mIdBit;
    final AtomicInteger mNextId;

    public MultiplexedStreamBroker(MessageChannel channel) throws IOException {
        if (channel == null) {
            throw new IllegalArgumentException();
        }
        mMessChannel = channel;

        mChannels = new IntHashMap<Chan>();
        mChannelsLock = new ReentrantReadWriteLock();

        mBufferPool = new BufferPool();

        mListeners = new LinkedBlockingQueue<StreamListener>();

        // Receives magic number and random number.
        class Bootstrap implements MessageReceiver {
            private volatile byte[] mMessage;

            private boolean mReceived;
            private boolean mClosed;
            private IOException mClosedException;

            Bootstrap() {
            }

            public MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer) {
                byte[] message;
                if (offset == 0) {
                    mMessage = message = new byte[totalSize];
                } else {
                    message = mMessage;
                }
                buffer.get(message, offset, buffer.remaining());
                return null;
            }

            public synchronized void process() {
                mReceived = true;
                notifyAll();
            }

            public synchronized void closed() {
                mClosed = true;
                process();
            }

            public synchronized void closed(IOException e) {
                mClosed = true;
                mClosedException = e;
                process();
            }

            public synchronized long getNumber() throws IOException {
                while (!mReceived) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }

                if (mClosed) {
                    if (mClosedException == null) {
                        throw new ClosedChannelException();
                    }
                    throw mClosedException;
                }

                ByteBuffer buffer = ByteBuffer.wrap(mMessage);

                long magic = buffer.getLong();
                if (magic != MAGIC_NUMBER) {
                    throw new IOException("Unknown magic number: " + magic);
                }

                return buffer.getLong();
            }
        };

        Bootstrap bootstrap = new Bootstrap();
        channel.receive(bootstrap);

        // Write magic number, followed by random number.
        long ourRnd = new SecureRandom().nextLong();
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(MAGIC_NUMBER);
        buffer.putLong(ourRnd);
        buffer.flip();
        channel.send(buffer);

        long theirRnd = bootstrap.getNumber();

        // The random numbers determine whether our channel ids are even or odd.
            
        if (ourRnd == theirRnd) {
            // What are the odds?
            throw new IOException("Negotiation failure");
        }

        mNextId = new AtomicInteger(mIdBit = (ourRnd < theirRnd ? 0 : 1));

        channel.receive(new Receiver());
    }

    public void accept(StreamListener listener) {
        mListeners.add(listener);
    }

    /**
     * Returned channel is not established until first written to.
     */
    public StreamChannel connect() {
        while (true) {
            int id = mNextId.getAndAdd(2) & 0x3fffffff;
            Chan chan = new Chan(id);
            Lock consWriteLock = mChannelsLock.writeLock();
            consWriteLock.lock();
            try {
                if (!mChannels.containsKey(id)) {
                    mChannels.put(id, chan);
                    return chan;
                }
            } finally {
                consWriteLock.unlock();
            }
        }
    }

    public void close() throws IOException {
        mChannelsLock.writeLock().lock();
        try {
            closed(null);
            mMessChannel.close();
        } finally {
            mChannelsLock.writeLock().unlock();
        }
    }

    public boolean isOpen() {
        return mMessChannel.isOpen();
    }

    public void execute(Runnable task) {
        mMessChannel.execute(task);
    }

    void sendMessage(ByteBuffer buffer) throws IOException {
        try {
            mMessChannel.send(buffer);
        } catch (IOException e) {
            closed(e);
            throw e;
        }
    }

    void closed(IOException exception) {
        mChannelsLock.writeLock().lock();
        try {
            // Clone to prevent concurrent modification.
            List<Chan> chans = new ArrayList<Chan>(mChannels.values());
            for (Chan chan : chans) {
                try {
                    chan.close(true, exception);
                } catch (IOException e) {
                    // Ignore.
                }
            }
            mChannels.clear();

            if (exception != null) {
                StreamListener listener;
                while ((listener = mListeners.poll()) != null) {
                    listener.failed(exception);
                }
            }
        } finally {
            mChannelsLock.writeLock().unlock();
        }
    }

    private class Receiver implements MessageReceiver {
        private int mHeader;
        private ByteBuffer mReceived;

        Receiver() {
        }

        public synchronized MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer) {
            MessageReceiver newReceiver;
            if (offset >= HEADER_SIZE) {
                newReceiver = null;
            } else {
                readHeader: {
                    if (offset == 0) {
                        if (totalSize > HEADER_SIZE) {
                            mReceived = mBufferPool.get(totalSize - HEADER_SIZE);
                        }
                        newReceiver = new Receiver();
                        if (buffer.remaining() >= HEADER_SIZE) {
                            mHeader = buffer.getInt();
                            break readHeader;
                        }
                    } else {
                        newReceiver = null;
                    }

                    int header = mHeader;
                    do {
                        header = (header << 8) | (buffer.get() & 0xff);
                        offset++;
                    } while (offset < HEADER_SIZE && buffer.remaining() > 0);

                    mHeader = header;
                }
            }

            if (mReceived != null) {
                mReceived.put(buffer);
            }

            return newReceiver;
        }

        public synchronized void process() {
            Chan chan;
            boolean newChan;
            int command;

            {
                int id = mHeader & 0x3fffffff;
                command = mHeader >>> 30;
                Lock chansReadLock = mChannelsLock.readLock();
                chansReadLock.lock();
                try {
                    chan = mChannels.get(id);
                } finally {
                    chansReadLock.unlock();
                }
                if (chan != null) {
                    newChan = false;
                } else {
                    Lock chansWriteLock = mChannelsLock.writeLock();
                    chansWriteLock.lock();
                    try {
                        if ((chan = mChannels.get(id)) != null) {
                            newChan = false;
                        } else if ((id & 1) == mIdBit) {
                            // Ignore command if peer is using a non-existent
                            // channel created by this broker.
                            return;
                        } else {
                            chan = new Chan(id);
                            mChannels.put(chan.getId(), chan);
                            newChan = true;
                        }
                    } finally {
                        chansWriteLock.unlock();
                    }
                }
            }

            if (command == ACK) {
                chan.acknowledged();
            }

            chan.received(mReceived);

            if (command == CLOSE) {
                try {
                    chan.close(true, null);
                } catch (IOException e) {
                    // Ignore.
                }
            }

            if (newChan) {
                try {
                    // FIXME: configurable timeout
                    StreamListener listener = mListeners.poll(10, TimeUnit.SECONDS);
                    if (listener != null) {
                        listener.established(chan);
                        return;
                    }
                } catch (InterruptedException e) {
                }

                // Not accepted in time, so close it.
                try {
                    chan.close(true, null);
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }

        public void closed() {
            MultiplexedStreamBroker.this.closed(null);
        }

        public void closed(IOException e) {
            MultiplexedStreamBroker.this.closed(e);
        }
    }

    private class Chan implements StreamChannel {
        final int mId;

        private final OutputStream mOut;
        private final ByteBuffer mOutBuffer;
        private boolean mOutBlocked;

        private final InputStream mIn;
        private ByteBuffer mInBuffer;
        private ByteBuffer mNextInBuffer;

        private boolean mClosed;
        private IOException mClosedException;

        private Object mLocalAddress;
        private Object mRemoteAddress;

        // FIXME: Use queue optimized for zero or one elements.
        private final ConcurrentLinkedQueue<StreamTask> mReadTasks;

        Chan(int id) {
            mId = id;

            mOut = new Out();
            mOutBuffer = mBufferPool.get(Math.min(65536, mMessChannel.getMaximumMessageSize()));
            mOutBuffer.putInt(id);

            mIn = new In();

            mReadTasks = new ConcurrentLinkedQueue<StreamTask>();
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        public synchronized Object getLocalAddress() {
            if (mLocalAddress == null) {
                mLocalAddress = new Object() {
                    @Override
                    public String toString() {
                        return String.valueOf(mMessChannel.getLocalAddress()) + '@' + mId;
                    }
                };
            }
            return mLocalAddress;
        }

        public synchronized Object getRemoteAddress() {
            if (mRemoteAddress == null) {
                mRemoteAddress = new Object() {
                    @Override
                    public String toString() {
                        return String.valueOf(mMessChannel.getRemoteAddress()) + '@' + mId;
                    }
                };
            }
            return mRemoteAddress;
        }

        public void executeWhenReadable(StreamTask task) {
            synchronized (mIn) {
                if (readAvailable() > 0) {
                    mMessChannel.execute(task);
                } else {
                    mReadTasks.add(task);
                }
            }
        }

        public boolean isOpen() {
            if (mClosed) {
                return false;
            }
            // Try again synchronized, since mClosed is not volatile.
            synchronized (mIn) {
                return !mClosed;
            }
        }

        public void execute(Runnable task) {
            mMessChannel.execute(task);
        }

        public void close() throws IOException {
            close(false, null);
        }

        void close(boolean force, IOException exception) throws IOException {
            // Must lock mIn before mOut to avoid deadlock with readBuffer method.
            synchronized (mIn) {
                synchronized (mOut) {
                    if (!mClosed) {
                        if (!force) {
                            ByteBuffer buffer = mOutBuffer;
                            buffer.put(0, (byte) ((CLOSE << 6) | buffer.get(0)));
                            flush(buffer);
                        }
                        mClosed = true;
                        mClosedException = exception;
                        mChannels.remove(mId);
                        mBufferPool.yield(mOutBuffer);
                    }
                    mOut.notifyAll();
                }
                mIn.notifyAll();
            }

            if (force) {
                StreamTask task;
                while ((task = mReadTasks.poll()) != null) {
                    if (exception == null) {
                        task.closed();
                    } else {
                        task.closed(exception);
                    }
                }
            }
        }

        @Override
        public String toString() {
            return "StreamChannel {localAddress=" + getLocalAddress() +
                ", remoteAddress=" + getRemoteAddress() + '}';
        }

        int getId() {
            return mId;
        }

        // Caller must synchronize on mOut.
        void write(int b) throws IOException {
            checkClosed();
            ByteBuffer buffer = mOutBuffer;
            if (!buffer.hasRemaining()) {
                flush(buffer);
            }
            buffer.put((byte) b);
        }

        // Caller must synchronize on mOut.
        void write(byte[] b, int off, int len) throws IOException {
            checkClosed();
            ByteBuffer buffer = mOutBuffer;
            if (len > 0) {
                while (true) {
                    int remaining = buffer.remaining();
                    if (remaining <= 0) {
                        flush(buffer);
                        remaining = buffer.remaining();
                    }
                    int amt = Math.min(remaining, len);
                    buffer.put(b, off, amt);
                    if ((len -= amt) <= 0) {
                        break;
                    }
                    off += amt;
                }
            }
        }

        // Caller must synchronize on mOut.
        void flush() throws IOException {
            checkClosed();
            ByteBuffer buffer = mOutBuffer;
            if (buffer.position() <= HEADER_SIZE) {
                return;
            }
            flush(buffer);
        }

        // Caller must synchronize on mOut.
        private void flush(ByteBuffer buffer) throws IOException {
            while (mOutBlocked) {
                checkClosed();
                try {
                    mOut.wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
                checkClosed();
                if (buffer.position() <= HEADER_SIZE) {
                    return;
                }
            }
            buffer.flip();
            try {
                sendMessage(buffer);
                mOutBlocked = true;
            } finally {
                buffer.limit(buffer.capacity()).position(HEADER_SIZE);
            }
        }

        void acknowledged() {
            synchronized (mOut) {
                if (mOutBlocked) {
                    mOutBlocked = false;
                    if (!mClosed) {
                        ByteBuffer buffer = mOutBuffer;
                        if (!buffer.hasRemaining()) {
                            try {
                                flush(buffer);
                            } catch (IOException e) {
                                // Ignore and assume all channels are now closed.
                            }
                        }
                    }
                    mOut.notifyAll();
                }
            }
        }

        void received(ByteBuffer buffer) {
            if (buffer == null) {
                return;
            }

            buffer.flip();
            boolean doSendAck;
            synchronized (mIn) {
                if (mInBuffer == null) {
                    doSendAck = true;
                    if (mNextInBuffer == null) {
                        mInBuffer = buffer;
                    } else {
                        mInBuffer = mNextInBuffer;
                        mNextInBuffer = buffer;
                    }
                } else {
                    doSendAck = false;
                    assert mNextInBuffer == null;
                    mNextInBuffer = buffer;
                }
                mIn.notify();
            }

            if (doSendAck) {
                sendAck();
            }

            StreamTask task = mReadTasks.poll();
            if (task != null) {
                task.run();
            }
        }

        private void sendAck() {
            synchronized (mOut) {
                if (!mClosed) {
                    ByteBuffer buffer = mOutBuffer;
                    int header = buffer.get(0) & 0x3f;
                    buffer.put(0, (byte) ((ACK << 6) | header));
                    int originalPos = buffer.position();
                    if (originalPos > HEADER_SIZE && !mOutBlocked) {
                        // Piggyback flush with acknowledgment.
                        buffer.flip();
                        originalPos = HEADER_SIZE;
                        mOutBlocked = true;
                    } else {
                        buffer.position(0).limit(HEADER_SIZE);
                    }
                    try {
                        sendMessage(buffer);
                    } catch (IOException e) {
                        // Ignore and assume all channels are now closed.
                    } finally {
                        buffer.put(0, (byte) header)
                            .limit(buffer.capacity()).position(originalPos);
                    }
                }
            }
        }

        // Caller must synchronize on mIn.
        int read() throws IOException {
            ByteBuffer buffer = readBuffer();
            byte b = buffer.get();
            if (!buffer.hasRemaining()) {
                mInBuffer = null;
                mBufferPool.yield(buffer);
            }
            return b & 0xff;
        }

        // Caller must synchronize on mIn.
        int read(byte[] b, int off, int len) throws IOException {
            ByteBuffer buffer = readBuffer();
            len = Math.min(len, buffer.remaining());
            buffer.get(b, off, len);
            if (!buffer.hasRemaining()) {
                mInBuffer = null;
                mBufferPool.yield(buffer);
            }
            return len;
        }

        // Caller must synchronize on mIn.
        int readAvailable() {
            ByteBuffer buffer = mInBuffer;
            int avail = buffer == null ? 0 : buffer.remaining();
            if ((buffer = mNextInBuffer) != null) {
                avail += mNextInBuffer.remaining();
            }
            return avail;
        }

        // Caller must synchronize on mIn.
        private ByteBuffer readBuffer() throws IOException {
            ByteBuffer buffer = mInBuffer;
            if (buffer == null) {
                if ((buffer = mNextInBuffer) != null) {
                    mInBuffer = buffer;
                    mNextInBuffer = null;
                    // This locks mOut while mIn lock is held. For this reason,
                    // double lock in close method must lock mIn and then mOut.

                    // TODO: More investigation required here. Can send buffer
                    // be full? If so, reads are blocked.
                    sendAck();
                } else {
                    do {
                        checkClosed();
                        try {
                            mIn.wait();
                        } catch (InterruptedException e) {
                            throw new InterruptedIOException();
                        }
                    } while ((buffer = mInBuffer) == null);
                }
            }
            return buffer;
        }

        // Caller must synchronize on mIn or mOut.
        private void checkClosed() throws ClosedChannelException {
            if (mClosed) {
                ClosedChannelException e = new ClosedChannelException();
                e.initCause(mClosedException);
                throw e;
            }
        }

        private class Out extends OutputStream {
            Out() {
            }

            @Override
            public synchronized void write(int b) throws IOException {
                Chan.this.write(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                Chan.this.write(b, off, len);
            }

            @Override
            public synchronized void flush() throws IOException {
                Chan.this.flush();
            }

            @Override
            public void close() throws IOException {
                // Note: This method is not synchronized. The Chan method does
                // the synchronization.
                Chan.this.close();
            }
        }

        private class In extends InputStream {
            In() {
            }

            @Override
            public synchronized int read() throws IOException {
                return Chan.this.read();
            }

            @Override
            public synchronized int read(byte[] b, int off, int len) throws IOException {
                return Chan.this.read(b, off, len);
            }

            @Override
            public synchronized int available() throws IOException {
                return Chan.this.readAvailable();
            }

            @Override
            public void close() throws IOException {
                // Note: This method is not synchronized. The Chan method does
                // the synchronization.
                Chan.this.close();
            }

            // FIXME: override skip
        }
    }
}
