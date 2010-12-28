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

package org.cojen.dirmi.core;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.ObjectStreamClass;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteFailure;

import org.cojen.dirmi.io.IOExecutor;

import org.cojen.dirmi.util.Cache;
import org.cojen.dirmi.util.ScheduledTask;

/**
 * Used by sessions to reduce overhead of serializing object stream class
 * descriptors. References to descriptors are used instead and cached.
 *
 * @author Brian S O'Neill
 */
class ClassDescriptorCache {
    private final IOExecutor mExecutor;
    private final ReadWriteLock mLock;
    private final Cache<Key, Reference> mRemoteReferences;

    private Handle mRemoteHandle;

    private Map<Key, ObjectStreamClass> mRequestedReferences;
    private Map<Key, Future> mInFlightRequests;

    private boolean mSendScheduled;

    /**
     * Create an unlinked ClassDescriptorCache.
     */
    ClassDescriptorCache(IOExecutor executor) {
        mExecutor = executor;
        mLock = new ReentrantReadWriteLock(false);
        mRemoteReferences = Cache.newSoftValueCache(17);
    }

    /**
     * Returns the local object to link with a remote ClassDescriptorCache.
     */
    public Handle localLink() {
        return new LocalHandle();
    }

    /**
     * Link to a remote ClassDescriptorCache.
     *
     * @throws IllegalStateException if already linked
     */
    public void link(Remote obj) {
        if (obj == null) {
            throw new IllegalArgumentException();
        }
        mLock.writeLock().lock();
        try {
            if (mRemoteHandle != null) {
                throw new IllegalStateException();
            }
            mRemoteHandle = (Handle) obj;
        } finally {
            mLock.writeLock().unlock();
        }
    }

    /**
     * Returns a remote reference to the given descriptor, or null if not
     * cached yet. Method should be invoked by writeClassDescriptor.
     */
    public Remote toReference(ObjectStreamClass desc) {
        Key key = new Key(desc);
        Object ref;
        Lock lock = mLock.readLock();
        lock.lock();
        try {
            return mRemoteReferences.get(key);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a local descriptor from the given reference. Method should be
     * invoked by readClassDescriptor.
     */
    public ObjectStreamClass toDescriptor(Remote ref) {
        // Cast to LocalReference to ensure not making a remote call.
        return ((LocalReference) ref).getDescriptor();
    }

    /**
     * Request that a reference be created to the given descriptor. Method
     * should be invoked by readClassDescriptor.
     */
    public void requestReference(ObjectStreamClass desc) {
        Key key = new Key(desc);

        Lock lock = mLock.writeLock();
        lock.lock();
        try {
            if (mRemoteHandle != null) {
                Map<Key, Future> inFlight = mInFlightRequests;
                if (inFlight != null && inFlight.containsKey(key)) {
                    return;
                }

                Map<Key, ObjectStreamClass> requested = mRequestedReferences;
                if (requested == null) {
                    mRequestedReferences = requested = new HashMap<Key, ObjectStreamClass>();
                }

                if (requested.put(key, desc) == null) {
                    scheduleSend(false);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void scheduleSend(boolean reschedule) {
        Lock lock = mLock.writeLock();
        lock.lock();
        try {
            if (mSendScheduled || mRequestedReferences == null) {
                if (reschedule) {
                    mSendScheduled = false;
                }
                return;
            }

            try {
                mSendScheduled = true;
                mExecutor.schedule(new ScheduledTask<RuntimeException>() {
                    protected void doRun() {
                        sendReferenceRequests();
                    }
                }, 10, TimeUnit.MILLISECONDS);
            } catch (RejectedException e) {
                mSendScheduled = false;
                // Requests failed, so remove them. They'll get requested again if
                // necessary.
                mRequestedReferences = null;
            }
        } finally {
            lock.unlock();
        }
    }

    void sendReferenceRequests() {
        try {
            Handle remoteHandle;
            ReferenceTransport[] refs;

            mLock.writeLock().lock();
            try {
                remoteHandle = mRemoteHandle;

                Map<Key, ObjectStreamClass> requested = mRequestedReferences;
                mRequestedReferences = null;
                if (requested == null || requested.isEmpty()) {
                    return;
                }

                Map<Key, Future> inFlight = mInFlightRequests;
                if (inFlight == null) {
                    mInFlightRequests = inFlight = new HashMap<Key, Future>();
                }

                refs = new ReferenceTransport[requested.size()];
                int i = 0;
                for (Map.Entry<Key, ObjectStreamClass> entry : requested.entrySet()) {
                    Key key = entry.getKey();
                    ObjectStreamClass desc = entry.getValue();
                    refs[i++] = new ReferenceTransport(desc);

                    // Create task to clean up in-flight entry if reference was
                    // received sooner than expected. It isn't a problem if
                    // this task runs too soon -- it just might cause redundant
                    // requests to be sent.
                    RemoveInFlight rif = new RemoveInFlight(key);
                    try {
                        Future future = mExecutor.schedule(rif, 5, TimeUnit.SECONDS);
                        inFlight.put(key, future);
                        rif.setFuture(future);
                    } catch (RejectedException e) {
                        // Schedule failure results in no entry at
                        // all. Redundant request might be sent.
                    }
                }
            } finally {
                mLock.writeLock().unlock();
            }

            try {
                remoteHandle.receive(refs);
            } catch (RemoteException e) {
                // If this happens, session will be closing down soon. No harm in
                // cleaning up sooner.
                mLock.writeLock().lock();
                try {
                    mInFlightRequests = null;
                } finally {
                    mLock.writeLock().unlock();
                }
            }
        } finally {
            scheduleSend(true);
        }
    }

    void receiveReferences(ReferenceTransport... refs) {
        Lock lock = mLock.writeLock();
        Cache<Key, Reference> remoteReferences = mRemoteReferences;
        for (ReferenceTransport ref : refs) {
            lock.lock();
            try {
                remoteReferences.put(ref.mKey, ref.mReference);
                removeInFlight(ref.mKey, null);
            } finally {
                lock.unlock();
            }
        }
    }

    void removeInFlight(Key key, Future expect) {
        Lock lock = mLock.writeLock();
        lock.lock();
        try {
            Map<Key, Future> inFlight = mInFlightRequests;
            if (inFlight != null) {
                if (expect == null || inFlight.get(key) == expect) {
                    Future task = inFlight.remove(key);
                    if (task != null) {
                        task.cancel(false);
                    }
                }
                if (inFlight.isEmpty()) {
                    mInFlightRequests = null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private class RemoveInFlight extends ScheduledTask<RuntimeException> {
        private final Key mKey;

        private Future mFuture;
        private boolean mFutureSet;

        RemoveInFlight(Key key) {
            mKey = key;
        }

        protected void doRun() {
            Future future;

            synchronized (this) {
                while (!mFutureSet) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                future = mFuture;
            }

            removeInFlight(mKey, future);
        }

        synchronized void setFuture(Future future) {
            mFuture = future;
            mFutureSet = true;
            notify();
        }
    }

    /**
     * Handle to a remote ClassDescriptorCache.
     */
    public static interface Handle extends Remote {
        void receive(ReferenceTransport... refs) throws RemoteException;
    }

    public static interface Reference extends Remote {
        // Never expected to be remotely called.
        @RemoteFailure(declared=false)
        public ObjectStreamClass getDescriptor();
    }

    private class LocalHandle implements Handle {
        @Override
        public void receive(ReferenceTransport... refs) {
            receiveReferences(refs);
        }
    }

    private static class LocalReference implements Reference {
        private final ObjectStreamClass mDescriptor;

        LocalReference(ObjectStreamClass desc) {
            mDescriptor = desc;
        }

        @Override
        public ObjectStreamClass getDescriptor() {
            return mDescriptor;
        }
    }

    private static class Key {
        final String mName;
        final long mId;

        Key(ObjectStreamClass desc) {
            mName = desc.getName();
            mId = desc.getSerialVersionUID();
        }

        Key(String name, long id) {
            mName = name;
            mId = id;
        }

        @Override
        public int hashCode() {
            return mName.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Key) {
                Key other = (Key) obj;
                return mName.equals(other.mName) && mId == other.mId;
            }
            return false;
        }

        @Override
        public String toString() {
            return mName + '/' + mId;
        }
    }

    public static class ReferenceTransport implements Externalizable {
        transient Key mKey;
        transient Reference mReference;

        // Need public constructor for Externalizable.
        public ReferenceTransport() {
        }

        ReferenceTransport(ObjectStreamClass desc) {
            mKey = new Key(desc);
            mReference = new LocalReference(desc);
        }

        @Override
        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(mKey.mName);
            out.writeLong(mKey.mId);
            out.writeObject(mReference);
        }

        @Override
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            mKey = new Key((String) in.readObject(), in.readLong());
            mReference = (Reference) in.readObject();
        }
    }
}
