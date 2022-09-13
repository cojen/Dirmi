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

/**
 * Specialized ItemMap for tracking Skeleton instances. Never put ordinary skeletons directly
 * into the map -- always call the skeletonFor method instead.
 *
 * @author Brian S O'Neill
 */
final class SkeletonMap extends ItemMap<Skeleton> {
    private final CoreSession mSession;
    private final long mIdType;

    private Entry[] mEntries;
    private int mSize;

    /**
     * @param idType IdGenerator.I_SERVER or IdGenerator.I_CLIENT
     */
    SkeletonMap(CoreSession session, long idType) {
        mSession = session;
        mIdType = idType;
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

        if (entries.length == INITIAL_CAPACITY) {
            Arrays.fill(entries, null);
        } else {
            mEntries = new Entry[INITIAL_CAPACITY];
        }
    }

    @Override
    synchronized Skeleton remove(long id) {
        Skeleton skeleton = super.remove(id);
        if (skeleton != null) {
            removeServer(skeleton.server());
        }
        return skeleton;
    }

    @Override
    synchronized void remove(Skeleton skeleton) {
        super.remove(skeleton.id);
        removeServer(skeleton.server());
    }

    /**
     * Returns the skeleton instance for the given server object, making it if necessary.
     */
    @SuppressWarnings("unchecked")
    <R> Skeleton<R> skeletonFor(R server) {
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
                    long id = IdGenerator.next(mIdType);
                    Skeleton<R> skeleton = factory.newSkeleton
                        (id, mSession.mSkeletonSupport, server);
                    VarHandle.storeStoreFence();

                    super.put(skeleton);

                    entry.mSkeletonOrLatch = skeleton;
                    latch.countDown();

                    return skeleton;
                } catch (Throwable e) {
                    // Calls countDown if necessary.
                    removeServer(server);
                    throw e;
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

    private synchronized void removeServer(Object server) {
        Entry found;
        find: {
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
                    found = e;
                    break find;
                }
                prev = e;
                e = next;
            }

            return;
        }

        Object skeletonOrLatch = found.mSkeletonOrLatch;
        
        if (skeletonOrLatch instanceof Skeleton) {
            super.remove((Skeleton) skeletonOrLatch);
        } else {
            ((CountDownLatch) skeletonOrLatch).countDown();
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
