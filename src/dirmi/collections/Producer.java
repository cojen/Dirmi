/*
 *  Copyright 2007 Brian S O'Neill
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

package dirmi.collections;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import java.util.concurrent.locks.ReentrantLock;

import dirmi.Asynchronous;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see RemoteConsumer
 */
public class Producer<E> {
    static final int INITIAL_CAPACITY = 64;

    static final AtomicReferenceFieldUpdater<Producer, Consumer.Channel> cChannelUpdater =
        AtomicReferenceFieldUpdater.newUpdater(Producer.class, Consumer.Channel.class, "mChannel");

    private static <E> BlockingQueue<E> checkQueue(BlockingQueue<E> queue) {
        if (queue == null) {
            throw new IllegalArgumentException("No local queue provided");
        }
        return queue;
    }

    private static int checkChunk(int chunk) {
        if (chunk <= 0) {
            throw new IllegalArgumentException("Chunk must be more than zero: " + chunk);
        }
        return chunk;
    }

    final BlockingQueue<E> mQueue;
    final Connecter<E> mConnecter;
    final ReentrantLock mPushLock = new ReentrantLock();

    volatile Consumer.Channel<E> mChannel;

    /**
     * Construct producer which is not yet connected to consumer.
     *
     * @param queue local queue
     */
    public Producer(BlockingQueue<E> queue) {
        mQueue = checkQueue(queue);
        mConnecter = new ConnecterServer();
    }

    /**
     * Construct producer which immediately connects to consumer.
     *
     * @param queue local queue
     * @param connecter for connecting to consumer
     */
    public Producer(BlockingQueue<E> queue, Consumer.Connecter<E> connecter)
        throws RemoteException
    {
        mQueue = checkQueue(queue);
        mConnecter = null;
        mChannel = connecter;
        connecter.connect(new ChannelServer());
    }

    public boolean offer(E element) {
        return mQueue.offer(element);
    }

    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        try {
            return mQueue.offer(element, timeout, unit);
        } catch (InterruptedException e) {
            // FIXME: close
            throw e;
        }
    }

    public void flush() throws RemoteException {
        if (acquirePushLock()) {
            try {
                E[] elements = drainAny();
                if (elements != null) {
                    channel().push(elements, false);
                }
            } finally {
                mPushLock.unlock();
            }
        }
    }

    public void close() throws RemoteException {
        // FIXME
    }

    /**
     * Returns the connector to pass to a remote server-side consumer.
     *
     * @throws IllegalStateException if producer is server-side
     */
    public Connecter<E> getConnecter() {
        if (mConnecter == null) {
            throw new IllegalStateException();
        }
        return mConnecter;
    }

    boolean acquirePushLock() {
        try {
            mPushLock.lockInterruptibly();
        } catch (InterruptedException e) {
            // FIXME: close
            return false;
        }
        return true;
    }

    // Caller must hold mPushLock. Method returns null if no elements.
    E[] drainAny() {
        return drain((E[]) new Object[INITIAL_CAPACITY], 0);
    }

    // Caller must hold mPushLock. Method returns null if interrupted.
    E[] drainAtLeastOne() {
        E element;
        try {
            element = mQueue.take();
        } catch (InterruptedException e) {
            return null;
        }

        // FIXME: handle "CLOSED" element

        E[] elements = (E[]) new Object[INITIAL_CAPACITY];
        elements[0] = element;

        // Gather up as much as possible.
        return drain(elements, 1);
    }

    // Caller must hold mPushLock.
    private E[] drain(E[] elements, int i) {
        BlockingQueue<E> queue = mQueue;
        
        E element;
        while ((element = queue.poll()) != null) {
            if (i >= elements.length) {
                int newCapacity = elements.length << 1;
                E[] newElements = (E[]) new Object[newCapacity];
                System.arraycopy(elements, 0, newElements, 0, elements.length);
                elements = newElements;
            }
            elements[i++] = element;
        }

        if (i < elements.length) {
            if (i == 0) {
                return null;
            }
            E[] newElements = (E[]) new Object[i];
            System.arraycopy(elements, 0, newElements, 0, i);
            elements = newElements;
        }

        return elements;
    }

    private Consumer.Channel channel() {
        Consumer.Channel channel = mChannel;
        if (channel == null) {
            throw new IllegalStateException("Not connected to consumer");
        }
        return channel;
    }

    public static interface Channel<E> extends Remote {
        /**
         * Pull elements from producer, blocking until at least one is
         * available.
         *
         * @return null if producer is closed or interrupted
         */
        E[] pull() throws RemoteException;

        /**
         * Request producer to push elements to consumer.
         */
        @Asynchronous
        void request() throws RemoteException;
    }

    public static interface Connecter<E> extends Channel<E> {
        /**
         * @throws IllegalStateException if already connected
         */
        @Asynchronous
        void connect(Consumer.Channel<E> channel) throws RemoteException;
    }

    private class ChannelServer implements Channel<E> {
        public E[] pull() {
            if (acquirePushLock()) {
                try {
                    return drainAny();
                } finally {
                    mPushLock.unlock();
                }
            } else {
                return null;
            }
        }

        public void request() {
            try {
                if (acquirePushLock()) {
                    try {
                        channel().push(drainAtLeastOne(), true);
                    } finally {
                        mPushLock.unlock();
                    }
                } else {
                    channel().push(null, true);
                }
            } catch (RemoteException e) {
                // FIXME: close
            }
        }
    }

    private class ConnecterServer extends ChannelServer implements Connecter<E> {
        public void connect(Consumer.Channel<E> channel) {
            if (!cChannelUpdater.compareAndSet(Producer.this, null, channel)) {
                throw new IllegalStateException("Already connected");
            }
        }
    }
}
