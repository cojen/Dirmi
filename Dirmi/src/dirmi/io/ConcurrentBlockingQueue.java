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

package dirmi.io;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple replacement for LinkedBlockingQueue which reduces the amount of locks.
 *
 * @author Brian S O'Neill
 */
class ConcurrentBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {
    private final ConcurrentLinkedQueue<E> mQueue;
    private final ReentrantLock mWaiterLock;
    private final Condition mWaiterCondition;

    public ConcurrentBlockingQueue() {
        this(new ReentrantLock());
    }

    /**
     * @param lock used to create condition object for signalling when entires
     * are added to queue
     */
    public ConcurrentBlockingQueue(ReentrantLock lock) {
        mQueue = new ConcurrentLinkedQueue<E>();
        mWaiterLock = lock;
        mWaiterCondition = lock.newCondition();
    }

    public boolean offer(E e) {
        mQueue.offer(e);
        // If there is one waiter and one offerer, then there should almost
        // never be any contention for this lock.
        mWaiterLock.lock();
        try {
            mWaiterCondition.signal();
        } finally {
            mWaiterLock.unlock();
        }
        return true;
    }

    public boolean offer(E e, long time, TimeUnit unit) {
        return offer(e);
    }

    public void put(E e) {
        offer(e);
    }

    public E peek() {
        return mQueue.peek();
    }

    public E poll() {
        return mQueue.poll();
    }

    public E poll(long time, TimeUnit unit) throws InterruptedException {
        if (time == 0) {
            return mQueue.poll();
        }
        long nanos = unit.toNanos(time);
        while (nanos > 0) {
            nanos = awaitNanos(nanos);
            E e = mQueue.poll();
            if (nanos <= 0 || e != null) {
                return e;
            }
        }
        return null;
    }

    public E take() throws InterruptedException {
        E e;
        if ((e = mQueue.poll()) == null) {
            mWaiterLock.lock();
            try {
                while ((e = mQueue.poll()) == null) {
                    mWaiterCondition.await();
                }
            } finally {
                mWaiterLock.unlock();
            }
        }
        return e;
    }

    public void await() throws InterruptedException {
        if (mQueue.isEmpty()) {
            mWaiterLock.lock();
            try {
                while (mQueue.isEmpty()) {
                    mWaiterCondition.await();
                }
            } finally {
                mWaiterLock.unlock();
            }
        }
    }

    public void await(long time, TimeUnit unit) throws InterruptedException {
        awaitNanos(unit.toNanos(time));
    }

    public long awaitNanos(long time) throws InterruptedException {
        if (mQueue.isEmpty()) {
            mWaiterLock.lock();
            try {
                while (mQueue.isEmpty()) {
                    if ((time = mWaiterCondition.awaitNanos(time)) <= 0) {
                        break;
                    }
                }
            } finally {
                mWaiterLock.unlock();
            }
        }
        return time;
    }

    public boolean isEmpty() {
        return mQueue.isEmpty();
    }

    public int size() {
        return mQueue.size();
    }

    public Iterator<E> iterator() {
        return mQueue.iterator();
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int max) {
        ConcurrentLinkedQueue<E> queue = mQueue;
        int count = 0;
        E e;
        while (count < max && (e = queue.poll()) != null) {
            c.add(e);
            count++;
        }
        return count;
    }

    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }
}
