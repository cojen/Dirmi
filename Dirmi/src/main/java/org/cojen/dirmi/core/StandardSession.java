/*
 *  Copyright 2006 Brian S O'Neill
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

import java.io.Closeable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.WriteAbortedException;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.cojen.classfile.TypeDesc;

import org.cojen.util.WeakValuedHashMap;
import org.cojen.util.SoftValuedHashMap;

import org.cojen.dirmi.Asynchronous;
import org.cojen.dirmi.ClassResolver;
import org.cojen.dirmi.Completion;
import org.cojen.dirmi.MalformedRemoteObjectException;
import org.cojen.dirmi.NoSuchClassException;
import org.cojen.dirmi.NoSuchObjectException;
import org.cojen.dirmi.ReconstructedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.Response;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.TimeoutParam;

import org.cojen.dirmi.info.RemoteInfo;
import org.cojen.dirmi.info.RemoteIntrospector;

import org.cojen.dirmi.io.Acceptor;
import org.cojen.dirmi.io.AcceptListener;
import org.cojen.dirmi.io.Broker;
import org.cojen.dirmi.io.Channel;
import org.cojen.dirmi.io.CloseListener;
import org.cojen.dirmi.io.StreamChannel;

import org.cojen.dirmi.util.ExceptionUtils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSession implements Session {
    static final int MAGIC_NUMBER = 0x7696b623;
    static final int PROTOCOL_VERSION = 20090205;

    private static final int DEFAULT_CHANNEL_IDLE_MILLIS = 60000;
    private static final int DISPOSE_BATCH = 1000;

    private static final AtomicReferenceFieldUpdater<StandardSession, ClassResolver>
        classResolverUpdater = AtomicReferenceFieldUpdater.newUpdater
        (StandardSession.class, ClassResolver.class, "mClassResolver");

    private static final AtomicIntegerFieldUpdater<StandardSession> closeStateUpdater =
        AtomicIntegerFieldUpdater.newUpdater(StandardSession.class, "mCloseState");

    final Broker<StreamChannel> mBroker;
    final ScheduledExecutorService mExecutor;

    final BlockingQueue<Object> mSendQueue = new ArrayBlockingQueue<Object>(1);

    // Queue for reclaimed phantom references.
    final ReferenceQueue<Object> mReferenceQueue;

    // Strong references to SkeletonFactories. SkeletonFactories are created as
    // needed and can be recreated as well. This map just provides quick
    // concurrent access to sharable SkeletonFactory instances.
    final ConcurrentMap<VersionedIdentifier, SkeletonFactory> mSkeletonFactories;

    // Cache of skeleton factories created for use by batched methods which
    // return a remote object. Must explicitly synchronize access.
    final Map<Identifier, SkeletonFactory> mRemoteSkeletonFactories;

    // Strong references to Skeletons. Skeletons are created as needed and can
    // be recreated as well. This map just provides quick concurrent access to
    // sharable Skeleton instances.
    final ConcurrentMap<VersionedIdentifier, Skeleton> mSkeletons;

    final SkeletonSupport mSkeletonSupport;

    // Cache of stub factories. Must explicitly synchronize access.
    final Map<VersionedIdentifier, StubFactory> mStubFactories;

    // Strong references to PhantomReferences to StubFactories.
    // PhantomReferences need to be strongly reachable or else they will be
    // reclaimed sooner than the referent becomes unreachable. When the
    // referent StubFactory becomes unreachable, its entry in this map must be
    // removed to reclaim memory.
    final ConcurrentMap<VersionedIdentifier, StubFactoryRef> mStubFactoryRefs;

    // Cache of stubs. Must explicitly synchronize access.
    final Map<VersionedIdentifier, Remote> mStubs;

    // Strong references to PhantomReferences to Stubs. PhantomReferences need
    // to be strongly reachable or else they will be reclaimed sooner than the
    // referent becomes unreachable. When the referent Stub becomes
    // unreachable, its entry in this map must be removed to reclaim memory.
    final ConcurrentMap<VersionedIdentifier, StubRef> mStubRefs;

    // Remote Admin object.
    final Hidden.Admin mRemoteAdmin;

    // Pool of channels for client calls.
    final LinkedList<InvocationChan> mChannelPool;

    // Thread local channel used with batch calls.
    final ThreadLocal<InvocationChannel> mLocalChannel;

    // Map of channels which are held by threads for batch calls.
    final Map<InvocationChannel, Thread> mHeldChannelMap;

    final ScheduledFuture<?> mBackgroundTask;

    volatile ClassResolver mClassResolver;

    final Object mCloseLock;
    private static final int STATE_CLOSING = 4, STATE_UNREF = 2, STATE_PEER_UNREF = 1;
    volatile int mCloseState;
    String mCloseMessage;

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     */
    public static Session create(ScheduledExecutorService executor, Broker<StreamChannel> broker)
        throws IOException
    {
        return new SessionRef(new StandardSession(executor, broker));
    }

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     */
    private StandardSession(ScheduledExecutorService executor, Broker<StreamChannel> broker)
        throws IOException
    {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }

        mCloseLock = new Object();

        mBroker = broker;
        mExecutor = executor;
        mReferenceQueue = new ReferenceQueue<Object>();

        mSkeletonFactories = new ConcurrentHashMap<VersionedIdentifier, SkeletonFactory>();
        mRemoteSkeletonFactories = new SoftValuedHashMap<Identifier, SkeletonFactory>();
        mSkeletons = new ConcurrentHashMap<VersionedIdentifier, Skeleton>();
        mSkeletonSupport = new SkeletonSupportImpl();

        mStubFactories = new SoftValuedHashMap<VersionedIdentifier, StubFactory>();
        mStubFactoryRefs = new ConcurrentHashMap<VersionedIdentifier, StubFactoryRef>();
        mStubs = new WeakValuedHashMap<VersionedIdentifier, Remote>();
        mStubRefs = new ConcurrentHashMap<VersionedIdentifier, StubRef>();

        mChannelPool = new LinkedList<InvocationChan>();
        mLocalChannel = new ThreadLocal<InvocationChannel>();
        mHeldChannelMap = Collections.synchronizedMap(new HashMap<InvocationChannel, Thread>());

        // Accept bootstrap request which replies with server and admin objects.
        broker.accept(new AcceptListener<StreamChannel>() {
            public void established(StreamChannel channel) {
                try {
                    InvocationChan chan = new InvocationChan(channel);
                    InvocationInputStream in = chan.getInputStream();
                    InvocationOutputStream out = chan.getOutputStream();

                    try {
                        int magic = in.readInt();
                        out.writeInt(MAGIC_NUMBER);
                        if (magic != MAGIC_NUMBER) {
                            return;
                        }
                        int version = in.readInt();
                        out.writeInt(PROTOCOL_VERSION);
                        if (version != PROTOCOL_VERSION) {
                            return;
                        }
                        out.writeUnshared(new AdminImpl());
                    } finally {
                        out.flush();
                    }
                } catch (Exception e) {
                    // Ignore. Let receive code detect communication error.
                } finally {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore.
                    }
                }
            }

            public void failed(IOException e) {
                // Ignore.
            }
        });

        // Send bootstrap request which receives server and admin objects.
        StreamChannel channel = broker.connect();
        try {
            InvocationChan chan = new InvocationChan(channel);
            InvocationOutputStream out = chan.getOutputStream();
            InvocationInputStream in = chan.getInputStream();

            out.writeInt(MAGIC_NUMBER);
            out.writeInt(PROTOCOL_VERSION);
            out.flush();

            int magic = in.readInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Incorrect magic number: " + magic);
            }
            int version = in.readInt();
            if (version != PROTOCOL_VERSION) {
                throw new IOException("Unsupported protocol version: " + version);
            }

            try {
                mRemoteAdmin = (Hidden.Admin) in.readUnshared();
            } catch (ClassNotFoundException e) {
                IOException io = new IOException();
                io.initCause(e);
                throw io;
            }
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                // Ignore.
            }
        }

        try {
            // Start background task.
            long delay = 5000; // FIXME: configurable?
            mBackgroundTask = executor.scheduleWithFixedDelay
                (new BackgroundTask(), delay, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            String message = "Unable to start background task";
            try {
                closeOnFailure(message, e);
            } catch (IOException e2) {
                // Ignore.
            }
            IOException io = new IOException(message);
            io.initCause(e);
            throw io;
        }

        // Begin accepting new requests.
        mBroker.accept(new Handler());
    }

    public void send(Object obj) throws RemoteException {
        send(obj, -1, null);
    }

    public void send(Object obj, long timeout, TimeUnit unit) throws RemoteException {
        if (timeout >= 0 && unit == null) {
            throw new NullPointerException("TimeUnit is null");
        }
        if (isClosing()) {
            throw new RemoteException("Session closed");
        }
        if (obj == null) {
            obj = new Null();
        }
        try {
            if (timeout < 0) {
                mSendQueue.put(obj);
            } else if (!mSendQueue.offer(obj, timeout, unit)) {
                throw new RemoteTimeoutException(timeout, unit);
            }
            if (isClosing()) {
                // Unblock any other threads blocked on send.
                while (mSendQueue.poll() != null);
                throw new RemoteException("Session closed");
            }
        } catch (RemoteException e) {
            try {
                closeOnFailure("Closed: " + e, e);
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        } catch (InterruptedException e) {
            try {
                closeOnFailure("Closed: " + e, e);
            } catch (IOException e2) {
                // Ignore.
            }
            throw new RemoteException(e.toString(), e);
        }
    }

    public Object receive() throws RemoteException {
        return receive(-1, null);
    }

    public Object receive(long timeout, TimeUnit unit) throws RemoteException {
        if (timeout >= 0 && unit == null) {
            throw new NullPointerException("TimeUnit is null");
        }
        try {
            Object obj;
            if (timeout < 0) {
                obj = mRemoteAdmin.dequeue();
            } else if ((obj = mRemoteAdmin.dequeue(timeout, unit)) == null) {
                throw new RemoteTimeoutException(timeout, unit);
            }
            return obj instanceof Null ? null : obj;
        } catch (RemoteException e) {
            try {
                closeOnFailure("Closed: " + e, e);
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        } catch (InterruptedException e) {
            try {
                closeOnFailure("Closed: " + e, e);
            } catch (IOException e2) {
                // Ignore.
            }
            throw new RemoteException(e.toString(), e);
        }
    }

    public void setClassResolver(ClassResolver resolver) {
        if (resolver == null) {
            resolver = ClassLoaderResolver.DEFAULT;
        }
        if (!classResolverUpdater.compareAndSet(this, null, resolver)) {
            throw new IllegalStateException("ClassResolver is already set");
        }
    }

    public void setClassLoader(ClassLoader loader) {
        if (loader == null) {
            setClassResolver(null);
        } else {
            setClassResolver(new ClassLoaderResolver(loader));
        }
    }

    public void flush() throws IOException {
        IOException exception = null;

        ArrayList<InvocationChannel> channels;
        synchronized (mChannelPool) {
            // Copy to avoid holding lock while flushing.
            channels = new ArrayList<InvocationChannel>(mChannelPool);
        }

        synchronized (mHeldChannelMap) {
            // Copy to avoid holding lock while flushing.
            channels.addAll(mHeldChannelMap.keySet());
        }

        for (int i=channels.size(); --i>=0; ) {
            try {
                channels.get(i).flush();
            } catch (IOException e) {
                if (exception == null) {
                    exception = e;
                }
            }
        }

        if (exception != null) {
            throw exception;
        }
    }

    public void close() throws IOException {
        close(true, true, null, null);
    }

    void closeOnFailure(String message, Throwable exception) throws IOException {
        close(false, false, message, exception);
    }

    void peerClosed(String message) {
        try {
            close(false, true, message, null);
        } catch (IOException e) {
            // Ignore.
        }
    }

    private void close(boolean notify, boolean explicit, String message, Throwable exception)
        throws IOException
    {
        if (message == null) {
            message = "Session closed";
        }

        // Use lock as a barrier to prevent race condition with adding entries
        // to mSkeletons.
        synchronized (mCloseLock) {
            if (isClosing()) {
                return;
            }
            // Null during close.
            mCloseMessage = null;
            setCloseState(STATE_CLOSING);
        }

        try {
            ScheduledFuture<?> task = mBackgroundTask;
            if (task != null) {
                task.cancel(false);
            }

            if (notify && mRemoteAdmin != null) {
                if (explicit) {
                    try {
                        mRemoteAdmin.closedExplicitly();
                    } catch (RemoteException e) {
                        // Ignore.
                    }
                } else {
                    try {
                        mRemoteAdmin.closedOnFailure(message, exception);
                    } catch (RemoteException e) {
                        // Perhaps exception is not serializable?
                        if (exception != null) {
                            try {
                                mRemoteAdmin.closedOnFailure(message, null);
                            } catch (RemoteException e2) {
                                // Ignore.
                            }
                        }
                        throw e;
                    }
                }
            }

            mBroker.close();
        } finally {
            clearCollections();
            mCloseMessage = message;
            // Volatile barrier.
            setCloseState(STATE_CLOSING);

            // Unblock up any threads blocked on send.
            while (mSendQueue.poll() != null);
        }
    }

    boolean isClosing() {
        return (mCloseState & STATE_CLOSING) != 0;
    }

    void setCloseState(int mask) {
        while (true) {
            int state = mCloseState;
            if (closeStateUpdater.compareAndSet(this, state, state | mask)) {
                break;
            }
        }
    }

    // Called by SessionRef.
    void sessionUnreferenced() {
        setCloseState(STATE_UNREF);
        try {
            mRemoteAdmin.sessionUnreferenced();
        } catch (RemoteException e) {
            // Ignore.
        }
    }

    // Should only be called by close method.
    private void clearCollections() {
        mSkeletonFactories.clear();
        mStubFactoryRefs.clear();
        mStubRefs.clear();

        for (Skeleton skeleton : mSkeletons.values()) {
            unreferenced(skeleton);
        }

        mSkeletons.clear();

        synchronized (mRemoteSkeletonFactories) {
            mRemoteSkeletonFactories.clear();
        }
        synchronized (mStubFactories) {
            mStubFactories.clear();
        }
        synchronized (mStubs) {
            mStubs.clear();
        }
    }

    void unreferenced(Skeleton skeleton) {
        try {
            skeleton.unreferenced();
        } catch (Throwable e) {
            uncaughtException(e);
        }
    }

    boolean addSkeleton(VersionedIdentifier objID, Skeleton skeleton) {
        // FIXME: Handle rare objID collision caused by mixing of remotely
        // generated ids and locally generated ids for batch calls. Perhaps if
        // key is composite of objID and typeID, effective number of bits is
        // 128, and so collision is practically impossible.

        // Use lock as a barrier to prevent race condition with close
        // method. Skeletons should not be added to mSkeletons if they are
        // going to be immediately unreferenced. Otherwise, there is a
        // possibility that the unreferenced method is not called.
        synchronized (mCloseLock) {
            if (!isClosing()) {
                mSkeletons.putIfAbsent(objID, skeleton);
                return true;
            }
        }

        unreferenced(skeleton);
        return false;
    }

    public Object getRemoteAddress() {
        return mBroker.getRemoteAddress();
    }

    public Object getLocalAddress() {
        return mBroker.getLocalAddress();
    }

    @Override
    public String toString() {
        return "Session {localAddress=" + getLocalAddress() +
            ", remoteAddress=" + getRemoteAddress() + '}';
    }

    void sendDisposedStubs() {
        if (mRemoteAdmin == null) {
            return;
        }

        while (true) {
            // Gather batch of stubs to be disposed.
            ArrayList<VersionedIdentifier> disposedList = new ArrayList<VersionedIdentifier>();

            Reference<?> ref;
            while (disposedList.size() < DISPOSE_BATCH && (ref = mReferenceQueue.poll()) != null) {
                VersionedIdentifier id = ((Ref) ref).unreachable();
                if (id != null) {
                    disposedList.add(id);
                }
            }

            int size = disposedList.size();
            if (size == 0) {
                return;
            }

            VersionedIdentifier[] disposed = new VersionedIdentifier[size];
            int[] localVersions = new int[size];
            int[] remoteVersions = new int[size];

            for (int i=0; i<size; i++) {
                VersionedIdentifier id = disposedList.get(i);
                disposed[i] = id;
                localVersions[i] = id.localVersion();
                remoteVersions[i] = id.remoteVersion();
            }

            try {
                mRemoteAdmin.disposed(disposed, localVersions, remoteVersions);
            } catch (RemoteException e) {
                if (!isClosing()) {
                    uncaughtException(e);
                }
            }
        }
    }

    void handleRequestsAsync(final InvocationChannel invChannel, final boolean reset) {
        mExecutor.execute(new Runnable() {
            public void run() {
                try {
                    if (reset) {
                        invChannel.getOutputStream().reset();
                    }
                    handleRequests(invChannel);
                } catch (IOException e) {
                    invChannel.disconnect();
                }
            }
        });
    }

    void handleRequests(InvocationChannel invChannel) {
        BatchedInvocationException batchedException = null;

        while (true) {
            final VersionedIdentifier objID;
            final int methodId;
            try {
                DataInput din = (DataInput) invChannel.getInputStream();
                objID = VersionedIdentifier.readAndUpdateRemoteVersion(din);
                methodId = din.readInt();
            } catch (IOException e) {
                invChannel.disconnect();
                return;
            }

            // Find a Skeleton to invoke.
            Skeleton skeleton = mSkeletons.get(objID);

            if (skeleton == null) {
                String message = "Cannot find remote object: " + objID;

                boolean synchronous = (methodId & 1) == 0;
                if (synchronous) {
                    // Try to inform caller of error, but no guarantee that
                    // this will work -- input arguments might exceed the size
                    // of the send/receive buffers.
                    Throwable t = new NoSuchObjectException(message);
                    try {
                        invChannel.getOutputStream().writeThrowable(t);
                        invChannel.flush();
                    } catch (IOException e) {
                        // Ignore.
                    }
                }

                // Connection must be closed in order to discard any unread
                // input arguments and piled up asynchronous calls.
                try {
                    invChannel.close();
                } catch (IOException e) {
                    // Ignore.
                }

                return;
            }

            try {
                Throwable throwable;

                try {
                    try {
                        if (skeleton.invoke(methodId, invChannel, batchedException)) {
                            // Handle another request.
                            batchedException = null;
                            continue;
                        }
                    } catch (BatchedInvocationException e) {
                        batchedException = e;
                        continue;
                    } catch (AsynchronousInvocationException e) {
                        throwable = null;
                        Throwable cause = e.getCause();
                        if (cause == null) {
                            cause = e;
                        }
                        uncaughtException(cause);
                        if (e.isRequestPending()) {
                            continue;
                        }
                    }
                    return;
                } catch (NoSuchMethodException e) {
                    throwable = e;
                } catch (NoSuchObjectException e) {
                    throwable = e;
                } catch (ClassNotFoundException e) {
                    throwable = e;
                } catch (NotSerializableException e) {
                    throwable = e;
                }

                InvocationOutputStream out = invChannel.getOutputStream();
                out.writeThrowable(throwable);
                out.flush();

                // Connection must be closed in order to discard any unread
                // input arguments and piled up asynchronous calls.
                invChannel.close();
            } catch (IOException e) {
                if (!isClosing()) {
                    uncaughtException(e);
                }
                invChannel.disconnect();
            }

            return;
        }
    }

    /**
     * @return null if none available
     */
    InvocationChannel getPooledChannel() throws IOException {
        if (isClosing()) {
            String message = mCloseMessage;
            if (message != null) {
                throw new IOException(message);
            }
        }

        InvocationChannel channel = mLocalChannel.get();
        if (channel != null) {
            return channel;
        }

        synchronized (mChannelPool) {
            if (mChannelPool.size() > 0) {
                return mChannelPool.removeLast();
            }
        }

        return null;
    }

    void holdLocalChannel(InvocationChannel channel) {
        mLocalChannel.set(channel);
        mHeldChannelMap.put(channel, Thread.currentThread());
    }

    void releaseLocalChannel() {
        InvocationChannel channel = mLocalChannel.get();
        mLocalChannel.remove();
        if (channel != null) {
            mHeldChannelMap.remove(channel);
        }
    }

    void uncaughtException(Throwable e) {
        try {
            Thread t = Thread.currentThread();
            t.getUncaughtExceptionHandler().uncaughtException(t, e);
        } catch (Throwable e2) {
            // I give up.
        }
    }

    /**
     * Used in conjunction with register. Synchronizes access to map.
     */
    static <K extends AbstractIdentifier, V> V lookup(Map<K, V> map, K key) {
        synchronized (map) {
            return map.get(key);
        }
    }

    /**
     * Used in conjunction with lookup. Synchronizes access to map.
     *
     * @return same value instance if registered; existing instance otherwise
     */
    static <K extends AbstractIdentifier, V> V register(Map<K, V> map, K key, V value) {
        synchronized (map) {
            V existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            map.put(key, value);
        }
        key.register(value);
        return value;
    }

    <R extends Remote> R createAndRegisterStub(VersionedIdentifier objID,
                                               StubFactory<R> factory,
                                               StubSupportImpl support)
    {
        R remote = factory.createStub(support);
        R existing = (R) register(mStubs, objID, remote);
        if (existing == remote) {
            mStubRefs.put(objID, new StubRef(remote, mReferenceQueue, support));
        } else {
            // Use existing instance instead.
            remote = existing;
        }
        return remote;
    }

    private static final class Null implements Serializable {}

    private static abstract class Ref<T> extends PhantomReference<T> {
        public Ref(T referent, ReferenceQueue queue) {
            super(referent, queue);
        }

        /**
         * @return null if already disposed of
         */
        protected abstract VersionedIdentifier unreachable();
    }

    private class StubFactoryRef extends Ref<StubFactory> {
        private final VersionedIdentifier mTypeID;

        StubFactoryRef(StubFactory factory, ReferenceQueue queue, VersionedIdentifier typeID) {
            super(factory, queue);
            mTypeID = typeID;
        }

        protected VersionedIdentifier unreachable() {
            return mTypeID;
        }
    }

    private static class StubRef extends Ref<Remote> {
        private final StubSupportImpl mStubSupport;

        StubRef(Remote stub, ReferenceQueue queue, StubSupportImpl support) {
            super(stub, queue);
            mStubSupport = support;
        }

        protected VersionedIdentifier unreachable() {
            return mStubSupport.unreachable();
        }
    }

    private static class Hidden {
        // Remote interface must be public, but hide it in a private class.
        public static interface Admin extends Remote {
            Object dequeue() throws RemoteException, InterruptedException;

            Object dequeue(@TimeoutParam long timeout, @TimeoutParam TimeUnit unit
                ) throws RemoteException, InterruptedException;

            /**
             * Returns RemoteInfo object from server.
             */
            RemoteInfo getRemoteInfo(VersionedIdentifier typeID) throws RemoteException;

            /**
             * Returns RemoteInfo object from server.
             */
            RemoteInfo getRemoteInfo(Identifier typeID) throws RemoteException;

            /**
             * Notification from client when it has disposed of identified objects.
             */
            void disposed(VersionedIdentifier[] ids, int[] localVersion, int[] remoteVersions)
                throws RemoteException;

            /**
             * Notification from client when session is not referenced any more.
             */
            @Asynchronous
            void sessionUnreferenced() throws RemoteException;

            /**
             * Notification from client when explicitly closed.
             */
            @Asynchronous
            void closedExplicitly() throws RemoteException;

            /**
             * Notification from client when closed due to an unexpected
             * failure.
             */
            @Asynchronous
            void closedOnFailure(String message, Throwable exception) throws RemoteException;
        }
    }

    private class AdminImpl implements Hidden.Admin {
        public Object dequeue() throws InterruptedException {
            return mSendQueue.take();
        }

        public Object dequeue(long timeout, TimeUnit unit) throws InterruptedException {
            return mSendQueue.poll(timeout, unit);
        }

        public RemoteInfo getRemoteInfo(VersionedIdentifier typeID) throws NoSuchClassException {
            Class remoteType = (Class) typeID.tryRetrieve();
            if (remoteType == null) {
                throw new NoSuchClassException("No Class found for id: " + typeID);
            }
            return RemoteIntrospector.examine(remoteType);
        }

        public RemoteInfo getRemoteInfo(Identifier typeID) throws NoSuchClassException {
            Class remoteType = (Class) typeID.tryRetrieve();
            if (remoteType == null) {
                throw new NoSuchClassException("No Class found for id: " + typeID);
            }
            return RemoteIntrospector.examine(remoteType);
        }

        /**
         * Note: Compared to the Admin interface, the names of version
         * arguments are swapped. This is because the local and remote
         * endpoints are now swapped.
         */
        public void disposed(VersionedIdentifier[] ids, int[] remoteVersions, int[] localVersions)
        {
            if (ids != null) {
                for (int i=0; i<ids.length; i++) {
                    dispose(ids[i], remoteVersions[i], localVersions[i]);
                }
            }
        }

        private void dispose(VersionedIdentifier id, int remoteVersion, int localVersion) {
            if (id.localVersion() != localVersion) {
                // If local version does not match, then stub for remote object
                // was disposed at the same time that remote object was
                // transported to the client. Client will generate a
                // replacement stub, and so object on server side cannot be
                // disposed. When new stub is garbage collected, another
                // disposed message is generated.

                // FIXME: Even if versions match, it doesn't totally prevent
                // the race condition. A lock must be held on skeleton maps
                // while this check is made.

                return;
            }

            int currentRemoteVersion;
            while ((currentRemoteVersion = id.remoteVersion()) != remoteVersion) {
                if (currentRemoteVersion > remoteVersion) {
                    // Disposed message was for an older stub instance. It is
                    // no longer applicable.
                    return;
                }

                // Disposed message arrived too soon. Wait a bit and let
                // version catch up. This case is not expected to occur when
                // synchronous calls are being made against the object.
                // Asynchronous calls don't keep a local reference to the stub
                // after sending a request, and so it can be garbage collected
                // before the request is received.

                // FIXME: After a few waits, give up and dispose anyhow.
                // Although this should not happen, garbage collector is
                // allowed to continue just in case it does.

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }

            mSkeletonFactories.remove(id);

            Skeleton skeleton = mSkeletons.remove(id);
            if (skeleton != null) {
                unreferenced(skeleton);
            }
        }

        public void sessionUnreferenced() {
            setCloseState(STATE_PEER_UNREF);
        }

        public void closedExplicitly() {
            peerClosed(null);
        }

        public void closedOnFailure(String message, Throwable exception) {
            String prefix = "Session closed by peer due to unexpected failure";
            message = message == null ? prefix : (prefix + ": " + message);
            peerClosed(message);
        }
    }

    private class BackgroundTask implements Runnable {
        BackgroundTask() {
        }

        public void run() {
            // Send batch of disposed ids to peer.
            sendDisposedStubs();

            // Release channels held by threads that exited.
            {
                ArrayList<InvocationChannel> released = null;

                synchronized (mHeldChannelMap) {
                    Iterator<Map.Entry<InvocationChannel, Thread>> it =
                        mHeldChannelMap.entrySet().iterator();

                    while (it.hasNext()) {
                        Map.Entry<InvocationChannel, Thread> entry = it.next();
                        if (!entry.getValue().isAlive()) {
                            InvocationChannel channel = entry.getKey();
                            if (released == null) {
                                released = new ArrayList<InvocationChannel>();
                            }
                            released.add(channel);
                            it.remove();
                        }
                    }
                }

                // Recycle or close channels outside of synchronized block.
                if (released != null) {
                    for (InvocationChannel channel : released) {
                        if (channel instanceof InvocationChan) {
                            ((InvocationChan) channel).recycle();
                        } else {
                            try {
                                channel.close();
                            } catch (IOException e2) {
                                // Ignore.
                            }
                        }
                    }
                }
            }

            // Close idle channels.
            while (true) {
                InvocationChan pooledChannel;
                synchronized (mChannelPool) {
                    pooledChannel = mChannelPool.peek();
                    if (pooledChannel == null) {
                        break;
                    }
                    long age = System.currentTimeMillis() - pooledChannel.getIdleTimestamp();
                    if (age < DEFAULT_CHANNEL_IDLE_MILLIS) {
                        break;
                    }
                    mChannelPool.remove();
                }
                try {
                    // Flush any lingering methods which used CallMode.EVENTUAL.
                    pooledChannel.flush();
                } catch (IOException e) {
                    // Ignore.
                } finally {
                    // Don't close, but disconnect idle channel to ensure
                    // underlying socket is really closed. Otherwise, it might
                    // go into another pool and sit idle. Besides, the remote
                    // endpoint responds to the closed channel by disconnecting
                    // it in the handleRequests method. This prevents socket
                    // pooling from working anyhow.
                    pooledChannel.disconnect();
                }
            }

            if (mStubRefs.size() <= 1 && mSkeletons.size() <= 1 &&
                (mCloseState & (STATE_UNREF | STATE_PEER_UNREF)) != 0)
            {
                // Session is completely unreferenced, so close it. The reason
                // why one stub and skeleton exists is because they are the admin
                // objects. The admin objects never get released automatically.
                try {
                    StandardSession.this.close();
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }
    }

    private class Handler implements AcceptListener<StreamChannel> {
        public void established(StreamChannel channel) {
            mBroker.accept(this);
            InvocationChannel invChan;
            try {
                invChan = new InvocationChan(channel);
            } catch (IOException e) {
                failed(e);
                return;
            }
            handleRequests(invChan);
        }

        public void failed(IOException e) {
            if (!isClosing()) {
                // This is just noise.
                //uncaughtException(new IOException("Failure accepting request", e));
                try {
                    closeOnFailure(e.getMessage(), e);
                } catch (IOException e2) {
                    // Ignore.
                }
            }
        }
    }

    static final AtomicReferenceFieldUpdater<InvocationChan, Future>
        timeoutTaskUpdater = AtomicReferenceFieldUpdater.newUpdater
        (InvocationChan.class, Future.class, "mTimeoutTask");

    static final Future<Object> timedOut = Response.complete(null);

    private final class InvocationChan extends AbstractInvocationChannel {
        private final StreamChannel mChannel;

        private volatile long mTimestamp;

        volatile Future<?> mTimeoutTask;

        InvocationChan(StreamChannel channel) throws IOException {
            super(new ResolvingObjectInputStream(channel.getInputStream()),
                  new ReplacingObjectOutputStream(channel.getOutputStream()));
            mChannel = channel;
        }

        public InvocationInputStream getInputStream() throws IOException {
            return mInvIn;
        }

        public InvocationOutputStream getOutputStream() throws IOException {
            return mInvOut;
        }

        public void close() throws IOException {
            final boolean wasOpen = replaceTimeout(null, 0, null) > 0;
            IOException exception = null;

            try {
                mInvOut.doClose();
            } catch (IOException e) {
                exception = e;
            }

            try {
                mInvIn.doClose();
            } catch (IOException e) {
                if (exception == null) {
                    exception = e;
                }
            }

            try {
                mChannel.close();
            } catch (IOException e) {
                if (exception == null) {
                    exception = e;
                }
            }

            if (wasOpen && exception != null) {
                mChannel.disconnect();
                throw exception;
            }
        }

        public boolean isOpen() {
            return mChannel.isOpen();
        }

        public void remoteClose() throws IOException {
            mChannel.remoteClose();
        }

        public void disconnect() {
            cancelTimeout();
            mChannel.disconnect();
        }

        public Closeable getCloser() {
            return mChannel;
        }

        public void addCloseListener(CloseListener listener) {
            mChannel.addCloseListener(listener);
        }

        public Object getLocalAddress() {
            return mChannel.getLocalAddress();
        }

        public Object getRemoteAddress() {
            return mChannel.getRemoteAddress();
        }

        public Throwable readThrowable() throws IOException, ReconstructedException {
            return getInputStream().readThrowable();
        }

        public void writeThrowable(Throwable t) throws IOException {
            getOutputStream().writeThrowable(t);
        }

        public boolean startTimeout(long timeout, TimeUnit unit) throws IOException {
            TimeoutTask task = new TimeoutTask();
            // Needs to be synchronized to prevent race with execution of task
            // not knowing its corresponding future. This blocks task execution
            // until future is set.
            synchronized (task) {
                try {
                    return replaceTimeout(task, timeout, unit) > 0;
                } catch (RejectedExecutionException e) {
                    throw new IOException("Unable to schedule timeout", e);
                }
            }
        }

        public boolean cancelTimeout() {
            return replaceTimeout(null, 0, null) != 0;
        }

        /**
         * @return -1: closed; 0: timed out; 1: replaced
         */
        private int replaceTimeout(TimeoutTask task, long timeout, TimeUnit unit)
            throws RejectedExecutionException
        {
            Future<?> existingTask;
            Future<?> futureTask = null;
            do {
                if (futureTask != null) {
                    futureTask.cancel(false);
                    task.setFuture(futureTask = null);
                }
                existingTask = mTimeoutTask;
                if (existingTask != null) {
                    existingTask.cancel(false);
                    if (existingTask == timedOut) {
                        return 0;
                    }
                }
                if (!isOpen()) {
                    return -1;
                }
                if (task != null) {
                    task.setFuture(futureTask = mExecutor.schedule(task, timeout, unit));
                }
            } while (!timeoutTaskUpdater.compareAndSet(this, existingTask, futureTask));
            return 1;
        }

        void timedOut(Future<?> expect) {
            if (timeoutTaskUpdater.compareAndSet(this, expect, timedOut)) {
                // Disconnect to force immediate wakeup of blocked call to socket.
                mChannel.disconnect();
            }
        }

        @Override
        public String toString() {
            String hashCode = Integer.toHexString(hashCode());
            return "Pipe@" + hashCode + " {localAddress=" + mChannel.getLocalAddress() +
                ", remoteAddress=" + mChannel.getRemoteAddress() + '}';
        }

        /**
         * Recycle the channel to be used for writing new requests.
         */
        final void recycle() {
            try {
                mTimestamp = System.currentTimeMillis();
                synchronized (mChannelPool) {
                    mChannelPool.add(this);
                }
            } catch (Exception e) {
                disconnect();
                // Ignore.
            }
        }

        long getIdleTimestamp() {
            return mTimestamp;
        }

        private class TimeoutTask implements Runnable {
            private Future<?> mMyFuture;

            public synchronized void run() {
                Future<?> myFuture = mMyFuture;
                if (myFuture != null) {
                    timedOut(myFuture);
                }
            }

            synchronized void setFuture(Future<?> future) {
                mMyFuture = future;
            }
        }
    }

    // TODO: Override writeClassDescriptor/readClassDescriptor to write
    // Identifiers for classes. This reduces the stream serialization
    // overhead. The protocol version will change when this is implemented, but
    // the old version should still be supported for backwards compatibility.

    private class ResolvingObjectInputStream extends ObjectInputStream {
        ResolvingObjectInputStream(InputStream in) throws IOException {
            super(in);
            enableResolveObject(true);
        }

        @Override
        protected void readStreamHeader() {
            // Do nothing and prevent deadlock when connecting.
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc)
            throws IOException, ClassNotFoundException
        {
            String name = desc.getName();
            if (name.length() == 0) {
                // This should not happen... let super class deal with it.
                return super.resolveClass(desc);
            }

            // Primitive and array types require special attention.

            TypeDesc type;
            if (name.charAt(0) == '[') {
                type = TypeDesc.forDescriptor(name);
            } else {
                type = TypeDesc.forClass(name);
            }

            if (type.isPrimitive()) {
                return type.toClass();
            } else if (type.isArray()) {
                TypeDesc root = type.getRootComponentType();
                if (root.isPrimitive()) {
                    return type.toClass();
                }
                int dims = type.getDimensions();
                type = TypeDesc.forClass(resolveClass(root.getRootName()));
                while (--dims >= 0) {
                    type = type.toArrayType();
                }
                return type.toClass();
            } else {
                return resolveClass(name);
            }
        }

        @Override
        protected Object resolveObject(Object obj) throws IOException {
            if (obj instanceof Marshalled) {
                if (obj instanceof MarshalledIntrospectionFailure) {
                    throw ((MarshalledIntrospectionFailure) obj).toException();
                }

                MarshalledRemote mr = (MarshalledRemote) obj;
                VersionedIdentifier objID = mr.mObjID;

                Remote remote;
                findRemote: {
                    remote = lookup(mStubs, objID);
                    if (remote != null) {
                        break findRemote;
                    }

                    Skeleton skeleton = mSkeletons.get(objID);
                    if (skeleton != null) {
                        remote = skeleton.getRemoteServer();
                        break findRemote;
                    }

                    VersionedIdentifier typeID = mr.mTypeID;
                    StubFactory factory = lookup(mStubFactories, typeID);

                    if (factory == null) {
                        RemoteInfo info = mr.mInfo;
                        if (info == null) {
                            info = mRemoteAdmin.getRemoteInfo(typeID);
                        }

                        Class type;
                        try {
                            type = resolveClass(info.getName());
                        } catch (ClassNotFoundException e) {
                            // Interface not found, but client can access
                            // methods via reflection.
                            type = Remote.class;
                        }

                        factory = StubFactoryGenerator.getStubFactory(type, info);

                        StubFactory existing = register(mStubFactories, typeID, factory);
                        if (existing == factory) {
                            mStubFactoryRefs.put
                                (typeID, new StubFactoryRef(factory, mReferenceQueue, typeID));
                        } else {
                            // Use existing instance instead.
                            factory = existing;
                        }
                    }

                    remote = createAndRegisterStub(objID, factory, new StubSupportImpl(objID));
                }

                obj = remote;
            }

            return obj;
        }

        private Class<?> resolveClass(String name) throws IOException, ClassNotFoundException {
            Class<?> clazz;
            ClassResolver resolver = mClassResolver;
            if (resolver == null || (clazz = resolver.resolveClass(name)) == null) {
                clazz = ClassLoaderResolver.DEFAULT.resolveClass(name);
            }
            return clazz;
        }
    }

    private class ReplacingObjectOutputStream extends ObjectOutputStream {
        ReplacingObjectOutputStream(OutputStream out) throws IOException {
            super(out);
            enableReplaceObject(true);
        }

        @Override
        protected void writeStreamHeader() {
            // Do nothing and prevent deadlock when connecting.
        }

        @Override
        protected Object replaceObject(Object obj) throws IOException {
            /*
              Replace objects which are Remote but not Serializable. In Java
              RMI, the user has a choice since objects must be explicitly
              exported as Remote. Since Dirmi automatically exports Remote
              objects as they are seen, it has to choose for itself. The
              options are:

              1. Require explicit Remote object export. This is the same
                 behavior as Java RMI. While it does give the user more
                 control, it makes the framework more difficult to use. As a
                 result, this option is rejected outright.

              2. Favor Remote interface. From a security perspective, this
                 appears to be safer. If a server received a Serializable
                 object when it expected a Remote object, it might allow client
                 side code to run in the server. Granted, this assumes that
                 remote class loading is enabled and client Dirmi code hasn't
                 been tampered with.

              3. Favor Serializable interface. If a Remote interface defines a
                 method whose result never changes per invocation, then it
                 makes little sense to remotely invoke it. The actual
                 implementation may instead choose to be Serializable. Methods
                 which still need to be remotely invoked delegate to a Remote
                 object which was transported with the Serializable wrapper.

              Because the second option provides no real security and the third
              option offers an actual performance benefit, the third option is
              chosen. If an object is Serializable, it cannot be Remote.
            */

            if (obj instanceof Remote && !(obj instanceof Serializable)) {
                Remote remote = (Remote) obj;
                VersionedIdentifier objID = VersionedIdentifier.identify(remote);

                Class remoteType;
                try {
                    remoteType = RemoteIntrospector.getRemoteType(remote);
                } catch (IllegalArgumentException e) {
                    return new MarshalledIntrospectionFailure
                        (e.getMessage(), obj.getClass().getName());
                }

                VersionedIdentifier typeID = VersionedIdentifier.identify(remoteType);
                RemoteInfo info = null;

                if (!mStubRefs.containsKey(objID) && !mSkeletons.containsKey(objID)) {
                    // Create skeleton for use by client. This also prevents
                    // remote object from being freed by garbage collector.

                    SkeletonFactory factory = mSkeletonFactories.get(typeID);
                    if (factory == null) {
                        try {
                            factory = SkeletonFactoryGenerator.getSkeletonFactory(remoteType);
                        } catch (IllegalArgumentException e) {
                            return new MarshalledIntrospectionFailure(e.getMessage(), remoteType);
                        }
                        SkeletonFactory existing = mSkeletonFactories.putIfAbsent(typeID, factory);
                        if (existing != null && existing != factory) {
                            factory = existing;
                        } else {
                            // Only send RemoteInfo for first use of exported
                            // object. If not sent, client will request it anyhow, so
                            // this is an optimization to avoid an extra round trip.
                            info = RemoteIntrospector.examine(remoteType);
                        }
                    }

                    Skeleton skeleton = factory.createSkeleton(mSkeletonSupport, remote);

                    if (!addSkeleton(objID, skeleton)) {
                        throw new RemoteException("Remote session is closing");
                    }
                }

                obj = new MarshalledRemote(objID, typeID, info);
            }

            return obj;
        }
    }

    private class SkeletonSupportImpl implements SkeletonSupport {
        public <V> void completion(Future<V> response, RemoteCompletion<V> completion)
            throws RemoteException
        {
            try {
                completion.complete(response == null ? null : response.get());
            } catch (InterruptedException e) {
                completion.exception(e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause == null) {
                    cause = e;
                }
                completion.exception(cause);
            }
        }

        public <R extends Remote> void linkBatchedRemote(Skeleton skeleton,
                                                         String skeletonMethodName,
                                                         Identifier typeID,
                                                         VersionedIdentifier remoteID,
                                                         Class<R> type, R remote)
            throws RemoteException
        {
            // Design note: Using a regular Identifier for the type to factory
            // mapping avoids collisions with stub factories which might be
            // registered against the same id. Stub factories are registered
            // with VersionedIdentifier, which uses a separate cache. Also, the
            // distributed garbage collector is not involved with reclaiming
            // skeleton factories for batch methods, and so the use of
            // VersionedIdentifier adds no value.

            SkeletonFactory factory = lookup(mRemoteSkeletonFactories, typeID);
            if (factory == null) {
                RemoteInfo remoteInfo = mRemoteAdmin.getRemoteInfo(typeID);
                factory = SkeletonFactoryGenerator.getSkeletonFactory(type, remoteInfo);

                SkeletonFactory existing = register(mRemoteSkeletonFactories, typeID, factory);
                if (existing != factory) {
                    // Use existing instance instead.
                    factory = existing;
                }
            }

            if (remote == null) {
                remote = failedBatchedRemote
                    (type, new NullPointerException
                     ("Batched method \"" + skeleton.getRemoteServer().getClass().getName() +
                      '.' + skeletonMethodName + "\" returned null"));
            }

            addSkeleton(remoteID, factory.createSkeleton(mSkeletonSupport, remote));
        }

        public <R extends Remote> R failedBatchedRemote(Class<R> type, final Throwable cause) {
            InvocationHandler handler = new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    throw cause;
                }
            };

            return (R) Proxy.newProxyInstance
                (getClass().getClassLoader(), new Class[] {type}, handler);
        }

        public boolean finished(InvocationChannel channel, boolean reset, boolean synchronous) {
            if (synchronous) {
                try {
                    channel.getOutputStream().flush();
                    if (reset) {
                        // Reset after flush to avoid blocking if send buffer
                        // is full. Otherwise, call could deadlock if reader is
                        // not reading the response from the next invocation on
                        // this channel. The reset operation will write data,
                        // but it won't be immediately flushed out of the
                        // channel's buffer. The buffer needs to have at least
                        // two bytes to hold the TC_ENDBLOCKDATA and TC_RESET
                        // opcodes.
                        channel.getOutputStream().reset();
                    }
                    return true;
                } catch (IOException e) {
                    channel.disconnect();
                    return false;
                }
            } else {
                try {
                    // Let another thread process next requests while this
                    // thread continues to process active request.
                    handleRequestsAsync(channel, reset);
                    return false;
                } catch (RejectedExecutionException e) {
                    return true;
                }
            }
        }
    }

    private class StubSupportImpl implements StubSupport {
        private final VersionedIdentifier mObjID;

        StubSupportImpl(VersionedIdentifier id) {
            mObjID = id;
        }

        // Used by batched methods that return a remote object.
        private StubSupportImpl() {
            mObjID = VersionedIdentifier.identify(this);
        }

        public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx) throws T {
            InvocationChannel channel;
            try {
                channel = getChannel();
            } catch (IOException e) {
                throw failed(remoteFailureEx, null, e);
            }

            try {
                mObjID.writeWithNextVersion((DataOutput) channel.getOutputStream());
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e);
            }

            return channel;
        }

        public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx,
                                                              long timeout, TimeUnit unit)
            throws T
        {
            if (timeout <= 0) {
                if (timeout < 0) {
                    return invoke(remoteFailureEx);
                } else {
                    // Fail immediately if zero timeout.
                    throw failed(remoteFailureEx, null, new RemoteTimeoutException(timeout, unit));
                }
            }

            InvocationChannel channel = getChannel(remoteFailureEx, timeout, unit);

            try {
                mObjID.writeWithNextVersion((DataOutput) channel.getOutputStream());
            } catch (IOException e) {
                throw failedAndCancelTimeout(remoteFailureEx, channel, e, timeout, unit);
            }

            return channel;
        }

        public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx,
                                                              double timeout, TimeUnit unit)
            throws T
        {
            if (timeout <= 0) {
                if (timeout < 0) {
                    return invoke(remoteFailureEx);
                } else {
                    // Fail immediately if zero timeout.
                    throw failed(remoteFailureEx, null, new RemoteTimeoutException(timeout, unit));
                }
            }

            // Convert timeout to nanoseconds.

            double factor;
            switch (unit) {
            case NANOSECONDS:
                factor = 1e0;
                break;
            case MICROSECONDS:
                factor = 1e3;
                break;
            case MILLISECONDS:
                factor = 1e6;
                break;
            case SECONDS:
                factor = 1e9;
                break;
            case MINUTES:
                factor = 1e9 * 60;
                break;
            case HOURS:
                factor = 1e9 * 60 * 60;
                break;
            case DAYS:
                factor = 1e9 * 60 * 60 * 24;
                break;
            default:
                throw new IllegalArgumentException(unit.toString());
            }

            long nanoTimeout = (long) (timeout * factor);

            InvocationChannel channel = getChannel
                (remoteFailureEx, nanoTimeout, TimeUnit.NANOSECONDS);

            try {
                mObjID.writeWithNextVersion((DataOutput) channel.getOutputStream());
            } catch (IOException e) {
                throw failedAndCancelTimeout(remoteFailureEx, channel, e, timeout, unit);
            }

            return channel;
        }

        public <V> Completion<V> createCompletion() {
            return new RemoteCompletionServer<V>();
        }

        public <T extends Throwable, R extends Remote> R
            createBatchedRemote(Class<T> remoteFailureEx,
                                InvocationChannel channel, Class<R> type) throws T
        {
            RemoteInfo info;
            try {
                info = RemoteIntrospector.examine(type);
            } catch (IllegalArgumentException e) {
                Throwable cause = new MalformedRemoteObjectException(e.getMessage(), type);
                throw failed(remoteFailureEx, channel, cause);
            }
            
            StubFactory<R> factory = StubFactoryGenerator.getStubFactory(type, info);

            StubSupportImpl support = new StubSupportImpl();
            Identifier typeId = Identifier.identify(type);

            // Write the type id and versioned object identifier.
            try {
                typeId.write(channel);
                support.mObjID.writeWithNextVersion((DataOutput) channel.getOutputStream());
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e);
            }

            return createAndRegisterStub(support.mObjID, factory, support);
        }

        public void batched(InvocationChannel channel) {
            holdLocalChannel(channel);
        }
 
        public void batchedAndCancelTimeout(InvocationChannel channel) {
            if (channel.cancelTimeout()) {
                holdLocalChannel(channel);
            } else {
                channel.disconnect();
            }
        }

        public void finished(InvocationChannel channel) {
            releaseLocalChannel();
            if (channel instanceof InvocationChan) {
                ((InvocationChan) channel).recycle();
            } else {
                channel.disconnect();
            }
        }

        public void finishedAndCancelTimeout(InvocationChannel channel) {
            releaseLocalChannel();
            if (channel.cancelTimeout() && channel instanceof InvocationChan) {
                ((InvocationChan) channel).recycle();
            } else {
                channel.disconnect();
            }
        }

        public <T extends Throwable> T failed(Class<T> remoteFailureEx,
                                              InvocationChannel channel,
                                              Throwable cause)
        {
            releaseLocalChannel();

            if (channel != null) {
                channel.disconnect();
            }

            if (cause instanceof ReconstructedException) {
                cause = ((ReconstructedException) cause).getCause();
            }

            if (cause != null && remoteFailureEx.isAssignableFrom(cause.getClass())) {
                return (T) cause;
            }

            RemoteException ex;
            if (cause == null) {
                ex = new RemoteException();
            } else if (cause instanceof RemoteTimeoutException) {
                ex = (RemoteException) cause;
            } else {
                String message = cause.getMessage();
                if (message == null || (message = message.trim()).length() == 0) {
                    message = cause.toString();
                }
                if (cause instanceof java.net.ConnectException) {
                    ex = new org.cojen.dirmi.ConnectException(message, (Exception) cause);
                } else if (cause instanceof java.net.UnknownHostException) {
                    ex = new org.cojen.dirmi.UnknownHostException(message, (Exception) cause);
                } else {
                    ex = new RemoteException(message, cause);
                }
            }

            if (!remoteFailureEx.isAssignableFrom(RemoteException.class)) {
                // Find appropriate constructor.
                for (Constructor ctor : remoteFailureEx.getConstructors()) {
                    Class[] paramTypes = ctor.getParameterTypes();
                    if (paramTypes.length != 1) {
                        continue;
                    }
                    if (paramTypes[0].isAssignableFrom(RemoteException.class)) {
                        try {
                            return (T) ctor.newInstance(ex);
                        } catch (Exception e) {
                        }
                    }
                }
            }

            return (T) ex;
        }

        public <T extends Throwable> T failedAndCancelTimeout(Class<T> remoteFailureEx,
                                                              InvocationChannel channel,
                                                              Throwable cause,
                                                              long timeout, TimeUnit unit)
        {
            if (!channel.cancelTimeout() && cause instanceof IOException) {
                // Since cancel task ran, assume cause is timeout.
                cause = new RemoteTimeoutException(timeout, unit);
            }
            return failed(remoteFailureEx, channel, cause);
        }

        public <T extends Throwable> T failedAndCancelTimeout(Class<T> remoteFailureEx,
                                                              InvocationChannel channel,
                                                              Throwable cause,
                                                              double timeout, TimeUnit unit)
        {
            if (!channel.cancelTimeout() && cause instanceof IOException) {
                // Since cancel task ran, assume cause is timeout.
                cause = new RemoteTimeoutException(timeout, unit);
            }
            return failed(remoteFailureEx, channel, cause);
        }

        private InvocationChannel getChannel() throws IOException {
            InvocationChannel channel = getPooledChannel();
            if (channel != null) {
                return channel;
            }
            return new InvocationChan(mBroker.connect());
        }

        private <T extends Throwable> InvocationChannel getChannel(Class<T> remoteFailureEx,
                                                                   long timeout, TimeUnit unit)
            throws T
        {
            InvocationChannel channel;
            try {
                channel = getPooledChannel();
            } catch (IOException e) {
                throw failed(remoteFailureEx, null, e);
            }

            if (channel == null) {
                try {
                    if (timeout < 0) {
                        channel = new InvocationChan(mBroker.connect());
                    } else {
                        long startNanos = System.nanoTime();
                        channel = new InvocationChan(mBroker.connect(timeout, unit));
                        long elapsedNanos = System.nanoTime() - startNanos;
                        if ((timeout -= unit.convert(elapsedNanos, TimeUnit.NANOSECONDS)) < 0) {
                            timeout = 0;
                        }
                    }
                } catch (IOException e) {
                    throw failed(remoteFailureEx, null, e);
                }
            }

            try {
                channel.startTimeout(timeout, unit);
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e);
            }

            return channel;
        }

        public int stubHashCode() {
            return mObjID.hashCode();
        }

        public boolean stubEquals(StubSupport support) {
            if (this == support) {
                return true;
            }
            if (support instanceof StubSupportImpl) {
                return mObjID.equals(((StubSupportImpl) support).mObjID);
            }
            return false;
        }

        public String stubToString() {
            return mObjID.toString();
        }

        VersionedIdentifier unreachable() {
            return mStubRefs.remove(mObjID) == null ? null : mObjID;
        }
    }
}
