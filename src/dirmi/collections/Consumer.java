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

import java.util.concurrent.Semaphore;

import dirmi.Asynchronous;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see RemoteProducer
 */
public class Consumer<E> {
    static final AtomicReferenceFieldUpdater<Consumer, Producer.Channel> cChannelUpdater =
        AtomicReferenceFieldUpdater.newUpdater(Consumer.class, Producer.Channel.class, "mChannel");

    private static <E> BlockingQueue<E> checkQueue(BlockingQueue<E> queue) {
        if (queue == null) {
            throw new IllegalArgumentException("No local queue provided");
        }
        return queue;
    }

    final BlockingQueue<E> mQueue;
    final Connecter<E> mConnecter;

    final Semaphore mRequestSemaphore = new Semaphore(20);

    volatile Producer.Channel<E> mChannel;

    /**
     * Construct consumer which is not yet connected to producer.
     *
     * @param queue local queue
     */
    public Consumer(BlockingQueue<E> queue) {
        mQueue = checkQueue(queue);
        mConnecter = new ConnecterServer();
    }

    /**
     * Construct consumer which immediately connects to producer.
     *
     * @param queue local queue
     * @param connecter for connecting to producer
     */
    public Consumer(BlockingQueue<E> queue, Producer.Connecter<E> connecter)
        throws RemoteException
    {
        mQueue = checkQueue(queue);
        mConnecter = null;
        mChannel = connecter;
        connecter.connect(new ChannelServer());
    }

    public E poll() throws RemoteException {
        E element = mQueue.poll();
        if (element == null) {
            doRequest();
        }
        return element;
    }

    public E poll(long timeout, TimeUnit unit) throws RemoteException, InterruptedException {
        E element = mQueue.poll();
        if (element != null) {
            return element;
        }
        doRequest();
        try {
            return mQueue.poll(timeout, unit);
        } catch (InterruptedException e) {
            // FIXME: close
            throw e;
        }
    }

    public void close() throws RemoteException {
        // FIXME
    }

    /**
     * Returns the connector to pass to a remote server-side producer.
     *
     * @throws IllegalStateException if consumer is server-side
     */
    public Connecter<E> getConnecter() {
        if (mConnecter == null) {
            throw new IllegalStateException();
        }
        return mConnecter;
    }

    /**
     * @return false if not connected to producer
     */
    boolean doRequest() throws RemoteException {
        Producer.Channel channel = mChannel;
        if (channel == null) {
            return false;
        }
        if (mRequestSemaphore.tryAcquire()) {
            try {
                channel.request();
            } catch (RemoteException e) {
                mRequestSemaphore.release();
                throw e;
            } catch (RuntimeException e) {
                mRequestSemaphore.release();
                throw e;
            } catch (Error e) {
                mRequestSemaphore.release();
                throw e;
            }
        }
        return true;
    }

    public static interface Channel<E> extends Remote {
        /**
         * Push elements into consumer.
         *
         * @param elements has at least one element; if null, producer is
         * interrupted or closed
         * @param requested is true if consumer requested elements
         */
        // This method must be not be asynchronous in order to guarantee
        // correct ordering of elements. It also serves as an automatic flow
        // control mechanism.
        void push(E[] elements, boolean requested) throws RemoteException;
    }

    public static interface Connecter<E> extends Channel<E> {
        /**
         * @throws IllegalStateException if already connected
         */
        @Asynchronous
        void connect(Producer.Channel<E> channel) throws RemoteException;
    }

    private class ChannelServer implements Channel<E> {
        public void push(E[] elements, boolean requested) {
            BlockingQueue<E> queue = mQueue;
            if (elements == null) {
                // FIXME: inject "CLOSED" into queue
            } else {
                if (requested) {
                    mRequestSemaphore.release();
                }
                try {
                    doRequest();
                } catch (RemoteException e) {
                    // Call to poll will issue another request and discover any exception.
                }
                System.out.println("element count: " + elements.length);
                try {
                    for (E element : elements) {
                        queue.put(element);
                    }
                } catch (InterruptedException e) {
                    // FIXME: close
                    return;
                }
            }
        }
    }

    private class ConnecterServer extends ChannelServer implements Connecter<E> {
        public void connect(Producer.Channel<E> channel) {
            if (!cChannelUpdater.compareAndSet(Consumer.this, null, channel)) {
                throw new IllegalStateException("Already connected");
            }
            try {
                doRequest();
            } catch (RemoteException e) {
                // Call to poll will issue another request and discover any exception.
            }
        }
    }
}
