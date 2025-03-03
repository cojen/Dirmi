/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

import java.lang.invoke.VarHandle;

import java.util.Arrays;

import java.util.concurrent.CountDownLatch;

import org.cojen.dirmi.SessionAware;

/**
 * Specialized ItemMap for tracking Skeleton instances. Never put canonical skeletons directly
 * into the map -- always call the skeletonFor method instead.
 *
 * @author Brian S O'Neill
 */
final class SkeletonMap extends ItemMap<Skeleton> {
    private final CoreSession mSession;

    private Entry[] mEntries;
    private int mSize;

    SkeletonMap(CoreSession session) {
        mSession = session;
        mEntries = new Entry[INITIAL_CAPACITY];
    }

    @Override
    synchronized void clear() {
        super.clear();

        Entry[] entries = mEntries;
        for (int i=0; i<entries.length; i++) {
            for (Entry e = entries[i]; e != null; e = e.mNext) {
                Object skeletonOrLatch = e.mSkeletonOrLatch;
                if (skeletonOrLatch instanceof CountDownLatch) {
                    ((CountDownLatch) skeletonOrLatch).countDown();
                }
            }
        }

        Arrays.fill(entries, null);
        mSize = 0;
    }

    @Override
    synchronized Skeleton remove(long id) {
        Skeleton skeleton = super.remove(id);
        if (id >= 0 && skeleton != null) { // only remove server if id isn't an alias
            skeleton.disposed();
            removeServer(skeleton.server());
        }
        return skeleton;
    }

    /**
     * Same as remove except doesn't call Skeleton.disposed, allowing it to be resurrected
     * later.
     *
     * @see AutoSkeleton
     */
    synchronized Skeleton removeAuto(long id) {
        Skeleton skeleton = super.remove(id);
        if (id >= 0 && skeleton != null) { // only remove server if id isn't an alias
            removeServer(skeleton.server());
        }
        return skeleton;
    }

    /**
     * Returns the skeleton instance for the given server object, making it if necessary.
     */
    <R> Skeleton<R> skeletonFor(R server) {
        return skeletonFor(server, true);
    }

    /**
     * Returns the skeleton instance for the given server object, optionally making it.
     */
    @SuppressWarnings("unchecked")
    <R> Skeleton<R> skeletonFor(R server, boolean make) {
        int hash = System.identityHashCode(server);

        while (true) {
            Entry entry;
            find: {
                // Quick find without synchronization.
                Entry[] entries = mEntries;
                for (Entry e = entries[hash & (entries.length - 1)]; e != null; e = e.mNext) {
                    if (e.mServer == server) {
                        entry = e;
                        break find;
                    }
                }

                CountDownLatch latch;

                synchronized (this) {
                    entries = mEntries;
                    int slot = hash & (entries.length - 1);
                    for (Entry e = entries[slot]; e != null; e = e.mNext) {
                        if (e.mServer == server) {
                            entry = e;
                            break find;
                        }
                    }

                    if (!make) {
                        return null;
                    }

                    int size = mSize;
                    if ((size + (size >> 1)) >= entries.length && grow()) {
                        entries = mEntries;
                        slot = hash & (entries.length - 1);
                    }

                    latch = new CountDownLatch(1);
                    entry = new Entry(server, latch);
                    entry.mNext = entries[slot];
                    VarHandle.storeStoreFence(); // ensure that entry fields are safely visible
                    entries[slot] = entry;

                    mSize = size + 1;
                }

                // Make the skeleton outside the synchronized block, because it can stall.

                try {
                    var type = (Class<R>) RemoteExaminer.remoteType(server);
                    SkeletonFactory<R> factory = SkeletonMaker.factoryFor(type);
                    long id = IdGenerator.nextPositive();
                    Skeleton<R> skeleton = factory.newSkeleton
                        (id, mSession.mSkeletonSupport, server);

                    if (server instanceof SessionAware sa) {
                        mSession.attachNotify(sa);
                    }

                    VarHandle.storeStoreFence();

                    super.put(skeleton);

                    entry.mSkeletonOrLatch = skeleton;

                    return skeleton;
                } catch (Throwable e) {
                    synchronized (this) {
                        removeServer(server);
                    }
                    throw e;
                } finally {
                    latch.countDown();
                }
            }

            Object skeletonOrLatch = entry.mSkeletonOrLatch;

            if (skeletonOrLatch instanceof Skeleton) {
                return (Skeleton<R>) skeletonOrLatch;
            }

            try {
                // Wait for another thread to do the work.
                ((CountDownLatch) skeletonOrLatch).await();
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Removes the server entry, but doesn't remove the superclass entry. Caller must be
     * synchronized.
     */
    private void removeServer(Object server) {
        Entry[] entries = mEntries;
        int slot = System.identityHashCode(server) & (entries.length - 1);

        for (Entry e = entries[slot], prev = null; e != null; ) {
            Entry next = e.mNext;
            if (e.mServer == server) {
                if (prev == null) {
                    entries[slot] = next;
                } else {
                    prev.mNext = next;
                }
                mSize--;
                e.mNext = null;
                return;
            }
            prev = e;
            e = next;
        }
    }

    private boolean grow() {
        Entry[] entries = mEntries;

        int capacity = entries.length << 1;
        if (capacity < 0) {
            return false;
        }

        var newEntries = new Entry[capacity];

        for (int i=0; i<entries.length; i++) {
            for (Entry e = entries[i]; e != null; ) {
                Entry next = e.mNext;
                int slot = System.identityHashCode(e.mServer) & (newEntries.length - 1);
                e.mNext = newEntries[slot];
                newEntries[slot] = e;
                e = next;
            }
        }

        mEntries = newEntries;

        return true;
    }

    private static final class Entry {
        final Object mServer;
        Object mSkeletonOrLatch;

        Entry mNext;

        Entry(Object server, CountDownLatch latch) {
            mServer = server;
            mSkeletonOrLatch = latch;
        }
    }
}
