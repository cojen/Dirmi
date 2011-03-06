/*
 *  Copyright 2006-2010 Brian S O'Neill
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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.Externalizable;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;

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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.cojen.classfile.TypeDesc;

import org.cojen.util.ThrowUnchecked;

import org.cojen.dirmi.Asynchronous;
import org.cojen.dirmi.ClassResolver;
import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Link;
import org.cojen.dirmi.MalformedRemoteObjectException;
import org.cojen.dirmi.NoSuchClassException;
import org.cojen.dirmi.NoSuchObjectException;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.ReconstructedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.Response;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.Timeout;
import org.cojen.dirmi.TimeoutParam;
import org.cojen.dirmi.Unbatched;
import org.cojen.dirmi.UnimplementedMethodException;

import org.cojen.dirmi.info.RemoteInfo;
import org.cojen.dirmi.info.RemoteIntrospector;

import org.cojen.dirmi.io.Channel;
import org.cojen.dirmi.io.ChannelAcceptor;
import org.cojen.dirmi.io.ChannelBroker;
import org.cojen.dirmi.io.CloseableGroup;
import org.cojen.dirmi.io.IOExecutor;

import org.cojen.dirmi.util.Cache;
import org.cojen.dirmi.util.ScheduledTask;
import org.cojen.dirmi.util.Timer;

/**
 * Standard implementation of a remote method invocation {@link Session}.
 *
 * @author Brian S O'Neill
 */
public class StandardSession implements Session {
    static final int MAGIC_NUMBER = 0x7696b623;
    static final int PROTOCOL_VERSION = 20100321;

    private static final int DEFAULT_CHANNEL_IDLE_SECONDS = 60;
    private static final int DISPOSE_BATCH = 1000;
    private static final int TIMEOUT_SECONDS = 15;

    private static final AtomicIntegerFieldUpdater<StandardSession> closeStateUpdater =
        AtomicIntegerFieldUpdater.newUpdater(StandardSession.class, "mCloseState");

    private static final AtomicIntegerFieldUpdater<StandardSession> supressPingUpdater =
        AtomicIntegerFieldUpdater.newUpdater(StandardSession.class, "mSupressPing");

    private final boolean mIsolated;

    final ChannelBroker mBroker;
    final IOExecutor mExecutor;

    final ClassDescriptorCache mDescriptorCache;

    final SessionExchanger mSessionExchanger = new SessionExchanger();

    // Queue for reclaimed phantom references.
    final ReferenceQueue<Object> mReferenceQueue;

    // Strong references to SkeletonFactories. SkeletonFactories are created as
    // needed and can be recreated as well. This map just provides quick
    // concurrent access to sharable SkeletonFactory instances.
    final ConcurrentMap<VersionedIdentifier, SkeletonFactory> mSkeletonFactories;

    // Cache of skeleton factories created for use by batched methods which
    // return a remote object. Must explicitly synchronize access.
    final Cache<Identifier, SkeletonFactory> mRemoteSkeletonFactories;

    // Strong references to Skeletons. Skeletons are created as needed and can
    // be recreated as well. This map just provides quick concurrent access to
    // sharable Skeleton instances.
    final ConcurrentMap<VersionedIdentifier, Skeleton> mSkeletons;

    final SkeletonSupport mSkeletonSupport;

    // Cache of stub factories. Must explicitly synchronize access.
    final Cache<VersionedIdentifier, StubFactory> mStubFactories;

    // Strong references to PhantomReferences to StubFactories.
    // PhantomReferences need to be strongly reachable or else they will be
    // reclaimed sooner than the referent becomes unreachable. When the
    // referent StubFactory becomes unreachable, its entry in this map must be
    // removed to reclaim memory.
    final ConcurrentMap<VersionedIdentifier, StubFactoryRef> mStubFactoryRefs;

    // Cache of stubs. Must explicitly synchronize access.
    final Cache<VersionedIdentifier, Remote> mStubs;

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

    // Clock is updated by background task to reduce system call overhead.
    // This clock is only as precise as the background task rate.
    volatile int mClockSeconds;
    final ScheduledFuture<?> mClockTask;
    final ScheduledFuture<?> mBackgroundTask;

    final RemoteTypeResolver mTypeResolver;

    volatile int mSupressPing;

    final Object mCloseLock;
    private static final int STATE_CLOSING = 4, STATE_UNREF = 2, STATE_PEER_UNREF = 1;
    volatile int mCloseState;
    String mCloseMessage;

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     */
    public static Session create(IOExecutor executor, ChannelBroker broker)
        throws IOException
    {
        return create(executor, broker, -1, null);
    }

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     */
    public static Session create(IOExecutor executor, ChannelBroker broker,
                                 long timeout, TimeUnit unit)
        throws IOException
    {
        Timer timer = timeout < 0 ? Timer.seconds(TIMEOUT_SECONDS) : new Timer(timeout, unit);
        return new SessionRef(new StandardSession(executor, broker, timer));
    }

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     */
    public static Session create(IOExecutor executor, ChannelBroker broker, Timer timer)
        throws IOException
    {
        return new SessionRef(new StandardSession(executor, broker, timer));
    }

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     */
    private StandardSession(IOExecutor executor, ChannelBroker broker, Timer timer)
        throws IOException
    {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }

        {
            /*
              TODO:

              By default, try to locate real objects only from this session
              instance. When false, a global object pool is accessed, allowing
              remote objects to pass between separate sessions without creating
              infinite proxy chains. This mode is off by default because it
              causes problems with unit tests which use local sessions.

              Isolated sessions also ensure that asynchronous methods behave as
              expected. They should always execute in a separate thread,
              avoiding potential deadlocks. As a compromise, another mode
              should be introduced which creates local stubs to objects found
              in the global pool. Asynchronous methods capture the arguments
              and execute the method in another thread.

              This new proposed mode should be safe to use as the default.
            */

            String isolated = null;
            try {
                isolated = System.getProperty("org.cojen.dirmi.isolatedSessions");
            } catch (SecurityException e) {
            }

            mIsolated = isolated == null || isolated.equalsIgnoreCase("true");
        }

        mTypeResolver = new RemoteTypeResolver();

        mCloseLock = new Object();

        mBroker = broker;
        mExecutor = executor;
        mDescriptorCache = new ClassDescriptorCache(executor);
        mReferenceQueue = new ReferenceQueue<Object>();

        mSkeletonFactories = new ConcurrentHashMap<VersionedIdentifier, SkeletonFactory>();
        mRemoteSkeletonFactories = Cache.newSoftValueCache(17);
        mSkeletons = new ConcurrentHashMap<VersionedIdentifier, Skeleton>();
        mSkeletonSupport = new SkeletonSupportImpl();

        mStubFactories = Cache.newSoftValueCache(17);
        mStubFactoryRefs = new ConcurrentHashMap<VersionedIdentifier, StubFactoryRef>();
        mStubs = Cache.newWeakValueCache(17);
        mStubRefs = new ConcurrentHashMap<VersionedIdentifier, StubRef>();

        mChannelPool = new LinkedList<InvocationChan>();
        mLocalChannel = new ThreadLocal<InvocationChannel>();
        mHeldChannelMap = Collections.synchronizedMap(new HashMap<InvocationChannel, Thread>());

        // Accept bootstrap request which replies with server and admin objects.
        class Bootstrap implements ChannelAcceptor.Listener {
            private boolean mFinished;

            public void accepted(Channel channel) {
                finished();
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
                } catch (IOException e) {
                    // Ignore. Let receive code detect communication error.
                } finally {
                    // Don't bother trying to recycle bootstrap channel.
                    // Recycling might depend on features which have not yet
                    // been initialized.
                    channel.disconnect();
                }
            }

            public void rejected(RejectedException e) {
                finished();
                // Ignore.
            }

            public void failed(IOException e) {
                finished();
                // Ignore.
            }

            public void closed(IOException e) {
                finished();
                // Ignore.
            }

            synchronized void waitToFinish(Timer timer) throws IOException {
                while (!mFinished) {
                    long remaining = RemoteTimeoutException.checkRemaining(timer);
                    long timeoutMillis = timer.unit().toMillis(remaining);
                    if (timeoutMillis == 0) {
                        timeoutMillis = 1;
                    }
                    try {
                        wait(timeoutMillis);
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
            }

            private synchronized void finished() {
                mFinished = true;
                notifyAll();
            }
        };

        Bootstrap bootstrap = new Bootstrap();
        broker.accept(bootstrap);

        // Send bootstrap request which receives server and admin objects.
        Channel channel = broker.connect(timer);
        try {
            InvocationChan chan = new InvocationChan(channel);
            InvocationOutputStream out = chan.getOutputStream();
            InvocationInputStream in = chan.getInputStream();

            chan.startTimeout(RemoteTimeoutException.checkRemaining(timer), timer.unit());

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

            chan.cancelTimeout();
        } finally {
            // Don't recycle bootstrap channel, as per comments above.
            channel.disconnect();
        }

        updateClock();

        try {
            // Start background tasks.
            mClockTask = executor.scheduleWithFixedDelay
                (new ScheduledTask<RuntimeException>() {
                    protected void doRun() {
                        updateClock();
                    }
                 }, 1, 1, TimeUnit.SECONDS);

            long delay = 5;
            mBackgroundTask = executor.scheduleWithFixedDelay
                (new BackgroundTask(), delay, delay, TimeUnit.SECONDS);
        } catch (RejectedException e) {
            String message = "Unable to start background task";
            closeOnFailure(message, e);
            throw new RejectedException(message, e);
        }

        // Wait for bootstrap to finish before installing regular request
        // handler. Although brokers usually pass accepted channels to handlers
        // in order of registration, there is no guarantee of this. Waiting
        // prevents regular handler from accepting the bootstrap request.
        bootstrap.waitToFinish(timer);

        // Begin accepting new requests.
        mBroker.accept(new Handler());

        // Link the ClassDescriptorCaches to enable them. This remote method is blocking
        // to ensure that when this constructor returns, remote method invocation has been
        // tested and at least one pooled connection is available.
        {
            ClassDescriptorCache.Handle handle = mDescriptorCache.localLink();
            try {
                mRemoteAdmin.linkDescriptorCache
                    (handle, RemoteTimeoutException.checkRemaining(timer), timer.unit());
            } catch (UnimplementedMethodException e) {
                // Fallback to original method.
                mRemoteAdmin.linkDescriptorCache(handle);
            }
        }            
    }

    public void send(Object obj) throws RemoteException {
        send(obj, -1, null);
    }

    public void send(Object obj, long timeout, TimeUnit unit) throws RemoteException {
        if (timeout >= 0 && unit == null) {
            throw new NullPointerException("TimeUnit is null");
        }
        if (isClosing()) {
            throw new ClosedException("Session closed");
        }
        try {
            if (!mSessionExchanger.enqueue(obj, timeout, unit)) {
                throw new RemoteTimeoutException(timeout, unit);
            }

            if (isClosing()) {
                // Unblock any other threads blocked on send.
                boolean anyDropped = false;
                while (mSessionExchanger.dequeue(null) != null) {
                    anyDropped = true;
                }
                if (anyDropped) {
                    throw new ClosedException("Session closed");
                }
            }
        } catch (RemoteException e) {
            closeOnFailure("Closed: " + e, e);
            throw e;
        } catch (InterruptedException e) {
            closeOnFailure("Closed: " + e, e);
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
            RemoteCompletionServer callback = new RemoteCompletionServer(null);
            Object obj = mRemoteAdmin.sessionDequeue(callback);

            if (obj == null) {
                try {
                    if (timeout < 0) {
                        obj = callback.get();
                    } else {
                        try {
                            obj = callback.get(timeout, unit);
                        } catch (TimeoutException e) {
                            throw new RemoteTimeoutException(timeout, unit);
                        }
                    }
                } catch (ExecutionException e) {
                    throw new RemoteException(e.getCause().getMessage(), e.getCause());
                }
            }

            if (obj instanceof SessionExchanger.Null) {
                obj = null;
            }

            return obj;
        } catch (RemoteException e) {
            closeOnFailure("Closed: " + e, e);
            throw e;
        } catch (InterruptedException e) {
            closeOnFailure("Closed: " + e, e);
            throw new RemoteException(e.toString(), e);
        }
    }

    public void setClassResolver(ClassResolver resolver) {
        mTypeResolver.setClassResolver(resolver);
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

    void closeOnFailure(String message, Throwable exception) {
        try {
            close(false, false, message, exception);
        } catch (IOException e) {
            // Ignore.
        }
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
            {
                ScheduledFuture<?> task = mClockTask;
                if (task != null) {
                    task.cancel(false);
                }
                task = mBackgroundTask;
                if (task != null) {
                    task.cancel(false);
                }
            }

            // Close broker now in case any discarded channels attempt to
            // recycle and try to make new connections.
            mBroker.close();

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

            {
                // Discard channels for which remote endpoint is read blocked.
                ArrayList<InvocationChan> toDiscard;
                synchronized (mChannelPool) {
                    toDiscard = new ArrayList<InvocationChan>(mChannelPool);
                    mChannelPool.clear();
                }

                for (InvocationChan chan : toDiscard) {
                    chan.discard();
                }
            }
        } finally {
            clearCollections();
            mCloseMessage = message;
            // Volatile barrier.
            setCloseState(STATE_CLOSING);

            // Unblock up any threads blocked on send.
            while (mSessionExchanger.dequeue(null) != null);
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

    boolean addSkeleton(VersionedIdentifier objId, Skeleton skeleton) {
        // FIXME: Handle rare objId collision caused by mixing of remotely
        // generated ids and locally generated ids for batch calls. Perhaps if
        // key is composite of objId and typeId, effective number of bits is
        // 128, and so collision is practically impossible.

        // Use lock as a barrier to prevent race condition with close
        // method. Skeletons should not be added to mSkeletons if they are
        // going to be immediately unreferenced. Otherwise, there is a
        // possibility that the unreferenced method is not called.
        synchronized (mCloseLock) {
            if (!isClosing()) {
                // Increment version early to prevent race condition with
                // disposal of new skeleton instance.
                objId.nextLocalVersion();
                mSkeletons.putIfAbsent(objId, skeleton);
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

    void sendDisposedStubs() throws RemoteException {
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

            mRemoteAdmin.disposed(disposed, localVersions, remoteVersions);
        }
    }

    void handleRequests(InvocationChannel invChannel) {
        BatchedInvocationException batchedException = null;

        while (true) {
            final VersionedIdentifier objId;
            final int methodId;
            Skeleton skeleton;
            try {
                DataInput din = invChannel.getInputStream();
                objId = VersionedIdentifier.read(din);
                int objVersion = din.readInt();
                methodId = din.readInt();
                skeleton = mSkeletons.get(objId);
                objId.updateRemoteVersion(objVersion);
            } catch (IOException e) {
                invChannel.disconnect();
                return;
            }

            skelCheck: if (skeleton == null) {
                if (!isClosing()) {
                    Object obj = objId.tryRetrieve();
                    if (obj instanceof Remote) {
                        // Re-create skeleton.
                        Remote remote = (Remote) obj;
                        Class remoteType = RemoteIntrospector.getRemoteType(remote);
                        VersionedIdentifier typeId = VersionedIdentifier.identify(remoteType);
                        SkeletonFactory factory = mSkeletonFactories.get(typeId);
                        if (factory == null) {
                            factory = SkeletonFactoryGenerator.getSkeletonFactory(remoteType);
                        }
                        SkeletonFactory existing = mSkeletonFactories.putIfAbsent(typeId, factory);
                        if (existing != null && existing != factory) {
                            factory = existing;
                        }
                        skeleton = factory.createSkeleton(objId, mSkeletonSupport, remote);
                        addSkeleton(objId, skeleton);
                        break skelCheck;
                    }

                    Throwable t = new NoSuchObjectException("Cannot find remote object: " + objId);

                    boolean synchronous = (methodId & 1) == 0;
                    if (synchronous) {
                        // Try to inform caller of error, but no guarantee that
                        // this will work -- input arguments might exceed the
                        // size of the send/receive buffers.
                        try {
                            invChannel.startTimeout(15, TimeUnit.SECONDS);
                            invChannel.getOutputStream().writeThrowable(t);
                            invChannel.flush();
                        } catch (IOException e) {
                            if (!isClosing()) {
                                uncaughtException(t);
                            }
                        }
                    } else {
                        if (!isClosing()) {
                            uncaughtException(t);
                        }
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
                        switch (skeleton.invoke(this, methodId, invChannel, batchedException)) {
                        case Skeleton.READ_FINISHED: default:
                            return;

                        case Skeleton.READ_ANY_THREAD:
                            if (invChannel.usesSelectNotification()) {
                                listenForRequestAsync(invChannel);
                                return;
                            }
                            // Fall through to next case.

                        case Skeleton.READ_SAME_THREAD:
                            batchedException = null;
                            continue;
                        }
                    } catch (BatchedInvocationException e) {
                        batchedException = e;
                        continue;
                    }
                } catch (NoSuchMethodException e) {
                    throwable = e;
                } catch (NoSuchObjectException e) {
                    throwable = e;
                } catch (StreamCorruptedException e) {
                    // Handle special case when cause is actually a closed channel.
                    String message = e.getMessage();
                    if (message != null && message.indexOf("EOF") >= 0) {
                        throw e;
                    }
                    throwable = e;
                } catch (ObjectStreamException e) {
                    throwable = e;
                } catch (MalformedRemoteObjectException e) {
                    throwable = e;
                } catch (IOException e) {
                    // General IOException is treated as communication failure.
                    throw e;
                } catch (Throwable e) {
                    throwable = e;
                }

                if (throwable instanceof IOException) {
                    Throwable cause = throwable.getCause();
                    if (cause instanceof EOFException) {
                        // Ordinary communication failure.
                        throw (IOException) throwable;
                    }
                }

                // Throwable might be caused by a client or server protocol
                // error, perhaps a malformed serializable parameter. In any
                // case, the only thing that can be safely done is disconnect
                // and report the throwable. Any attempt to report the problem
                // back to the client will likely fail, because the
                // communication stream is not in a known state.

                invChannel.disconnect();

                if (!isClosing()) {
                    uncaughtException(throwable);
                }
            } catch (IOException e) {
                /* This is just noise.
                if (!isClosing()) {
                    uncaughtException(e);
                }
                */
                invChannel.disconnect();
            }

            return;
        }
    }

    void readRequest(InvocationChannel invChannel) {
        if (invChannel.usesSelectNotification()) {
            listenForRequestAsync(invChannel);
        } else {
            handleRequests(invChannel);
        }
    }

    void resumeAndReadRequestAsync(final InvocationChannel invChannel) throws RejectedException {
        mExecutor.execute(new Runnable() {
            public void run() {
                if (!(invChannel instanceof InvocationChan)) {
                    invChannel.disconnect();
                    return;
                }

                InvocationChan chan = (InvocationChan) invChannel;

                tryResume: {
                    if (chan.inputResume()) {
                        break tryResume;
                    }
                    if (chan.isResumeSupported()) {
                        // Drain all extra client data.
                        try {
                            while (chan.skip(Integer.MAX_VALUE) > 0);
                        } catch (IOException e) {
                            chan.disconnect();
                            return;
                        }
                    }
                    if (chan.inputResume()) {
                        break tryResume;
                    }
                    chan.disconnect();
                    return;
                }

                // After reading EOF, ObjectInputStream is horked so replace it.
                try {
                    chan = new InvocationChan(chan);
                } catch (IOException e) {
                    chan.disconnect();
                    return;
                }

                readRequest(chan);
            }
        });
    }

    void listenForRequestAsync(final InvocationChannel invChannel) {
        invChannel.inputNotify(new Channel.Listener() {
            public void ready() {
                handleRequests(invChannel);
            }

            public void rejected(RejectedException cause) {
                closeDueToRejection(invChannel, cause);
            }

            public void closed(IOException cause) {
                // Client closed idle connection.
                invChannel.disconnect();
            }
        });
    }

    /*
     * All InvocationChan instances intended to be recycled must be created
     * through this method to ensure that recycled channels are paired properly
     * with the remote endpoint. InvocationChans created otherwise must
     * disconnected after one use, on both sides.
     */
    InvocationChan toInvocationChannel(Channel channel, final boolean accepted)
        throws IOException
    {
        Remote control = channel.installRecycler(new Channel.Recycler() {
            public void recycled(final Channel channel) {
                try {
                    mExecutor.execute(new Runnable() {
                        public void run() {
                            InvocationChan invChannel;
                            try {
                                invChannel = toInvocationChannel(channel, accepted);
                            } catch (IOException e) {
                                channel.disconnect();
                                return;
                            }

                            if (accepted) {
                                readRequest(invChannel);
                            } else {
                                invChannel.recycle();
                            }
                        }
                    });
                } catch (RejectedException e) {
                    channel.disconnect();
                }
            }
        });

        InvocationChan invChannel = new InvocationChan(channel);

        if (control == null) {
            return invChannel;
        }

        // Send remote control object without the help of replaceObject. Remote
        // types on both endpoints are assumed to be the same. This avoids race
        // conditions when sending remote type info, which might cause the
        // receiver to make remote call. In doing so, this can cause a stack
        // overflow or exhaustion of threads.

        Class controlType = RemoteIntrospector.getRemoteType(control);
        VersionedIdentifier controlTypeId = VersionedIdentifier.identify(controlType);
        {
            VersionedIdentifier controlId = VersionedIdentifier.identify(control);
            SkeletonFactory factory = mSkeletonFactories.get(controlTypeId);
            if (factory == null) {
                factory = SkeletonFactoryGenerator.getSkeletonFactory(controlType);
                SkeletonFactory existing = mSkeletonFactories.putIfAbsent(controlTypeId, factory);
                if (existing != null) {
                    factory = existing;
                }
            }
            Skeleton skeleton = factory.createSkeleton(controlId, mSkeletonSupport, control);
            addSkeleton(controlId, skeleton);
            controlId.writeWithNextVersion(invChannel);
            invChannel.flush();
        }

        // Receive remote control object and link to stub without using resolveObject.
        {
            VersionedIdentifier controlId = VersionedIdentifier.read(invChannel);
            int controlVersion = invChannel.readInt();
                
            StubFactory factory = mStubFactories.get(controlTypeId);
            if (factory == null) {
                RemoteInfo info = RemoteIntrospector.examine(controlType);
                factory = StubFactoryGenerator.getStubFactory(controlType, info);
                StubFactory existing = register(mStubFactories, controlTypeId, factory);
                if (existing == factory) {
                    mStubFactoryRefs.put
                        (controlTypeId, 
                         new StubFactoryRef(factory, mReferenceQueue, controlTypeId));
                } else {
                    factory = existing;
                }
            }

            controlId.updateRemoteVersion(controlVersion);
            Remote remoteControl = createAndRegisterStub
                (controlId, factory, new StubSupportImpl(controlId));
            channel.setRecycleControl(remoteControl);
        }

        return invChannel;
    }

    /**
     * @return null if none available
     */
    InvocationChannel getPooledChannel() throws IOException {
        if (isClosing()) {
            String message = mCloseMessage;
            if (message != null) {
                throw new ClosedException(message);
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
        if (channel != null) {
            mLocalChannel.remove();
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

    void checkCommunication() {
        if (isClosing()) {
            return;
        }

        if (!supressPingUpdater.compareAndSet(this, 0, 1)) {
            // Another ping was recently performed, so don't do another.
            return;
        }

        try {
            mExecutor.execute(new Runnable() {
                public void run() {
                    try {
                        try {
                            mRemoteAdmin.ping();
                        } catch (UnimplementedMethodException e) {
                            // Fallback to another method for compatibility.
                            try {
                                mRemoteAdmin.getRemoteInfo((Identifier) null);
                            } catch (NullPointerException e2) {
                                // Expected with null parameter.
                            }
                        }
                    } catch (RemoteException e) {
                        closeOnFailure("Ping failure", e);
                    }
                }
            });
        } catch (RejectedException e) {
            // Just wait for broker ping failure instead.
        }
    }

    void closeDueToRejection(Channel channel, RejectedException cause) {
        // 'Tis safer to close instead of trying later in the same thread. An
        // asynchronous method takes an indefinite amount of time to complete,
        // starving the next requests. It might be nice to send an exception to
        // the caller, but this isn't guaranteed to work in a timely fashion
        // either. The request arguments first need to be drained, but this can
        // block. If any pending requests are asynchronous, then an exception
        // cannot be sent back anyhow.
        if (!isClosing()) {
            IOException ex;
            if (cause.isShutdown()) {
                ex = new RejectedException
                    ("No threads available; closing channel: " + channel, cause);
            } else {
                ex = new RejectedException
                    ("Too many active threads; closing channel: " + channel, cause);
            }
            uncaughtException(ex);
        }
        channel.disconnect();
    }

    Remote findIdentifiedRemote(VersionedIdentifier objId) {
        Remote remote = mStubs.get(objId);

        if (remote == null) {
            if (mIsolated) {
                // Try to find real object from just this session.
                Skeleton skeleton = mSkeletons.get(objId);
                if (skeleton != null) {
                    remote = skeleton.getRemoteServer();
                }
            } else {
                // Try to find real object from global pool.
                Object retrieved = objId.tryRetrieve();
                if (retrieved instanceof Remote) {
                    remote = (Remote) retrieved;
                }
            }
        }

        return remote;
    }

    /**
     * @return same value instance if registered; existing instance otherwise
     */
    static <K extends AbstractIdentifier, V> V register(Cache<K, V> cache, K key, V value) {
        synchronized (cache) {
            V existing = cache.get(key);
            if (existing != null) {
                return existing;
            }
            cache.put(key, value);
        }
        key.register(value);
        return value;
    }

    <R extends Remote> R createAndRegisterStub(VersionedIdentifier objId,
                                               StubFactory<R> factory,
                                               StubSupportImpl support)
    {
        R remote = factory.createStub(support);
        R existing = (R) register(mStubs, objId, remote);
        if (existing == remote) {
            mStubRefs.put(objId, new StubRef(remote, mReferenceQueue, support));
        } else {
            // Use existing instance instead.
            remote = existing;
        }
        return remote;
    }

    void clearStub(VersionedIdentifier objId) {
        StubRef ref = mStubRefs.remove(objId);
        if (ref != null) {
            ref.clear();
        }
    }

    void updateClock() {
        mClockSeconds = (int) (System.nanoTime() / (1L * 1000 * 1000 * 1000));
    }

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
        private final VersionedIdentifier mTypeId;

        StubFactoryRef(StubFactory factory, ReferenceQueue queue, VersionedIdentifier typeId) {
            super(factory, queue);
            mTypeId = typeId;
        }

        protected VersionedIdentifier unreachable() {
            return mStubFactoryRefs.remove(mTypeId) == null ? null : mTypeId;
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

    static class Hidden {
        // Remote interface must be public, but hide it in a private class.
        public static interface Admin extends Remote {
            /**
             * @return object if immediately available
             */
            Object sessionDequeue(RemoteCompletion<Object> callback) throws RemoteException;

            /**
             * Returns RemoteInfo object from server. Method is unbatched to
             * ensure that it doesn't disrupt a batch in the current thread.
             */
            @Unbatched
            RemoteInfo getRemoteInfo(VersionedIdentifier typeId) throws RemoteException;

            /**
             * Returns RemoteInfo object from server. Method is unbatched to
             * ensure that it doesn't disrupt a batch in the current thread.
             */
            @Unbatched
            RemoteInfo getRemoteInfo(Identifier typeId) throws RemoteException;

            /**
             * Returns information regarding an unknown serialized class.
             * Method is unbatched to ensure that it doesn't disrupt a batch in
             * the current thread.
             *
             * <p>The last character in the returned signature is S for Serializable, E
             * for Externalizable, U for enum, and N for non-serializable. The signature
             * may also provide a superclass type and interface types. These types are
             * repsented as ordinary Java class name signatures. If the supertype is
             * Object, it is omitted from the signature. Likewise, additional interfaces
             * are ommitted if they are implied by the signature type.
             *
             * @return <serialVersionUID> ':' ('S' | 'E' | 'U' | 'N')
             * [ ':' [ <superclass type> ] ':' { <interface type> } ]
             */
            @Unbatched
            String getUnknownClassInfo(String name) throws RemoteException;

            /**
             * Notification from client when it has disposed of identified objects.
             */
            void disposed(VersionedIdentifier[] ids, int[] localVersion, int[] remoteVersions)
                throws RemoteException;

            void linkDescriptorCache(Remote link) throws RemoteException;

            void linkDescriptorCache(Remote link, @TimeoutParam long timeout, TimeUnit unit)
                throws RemoteException;

            @Timeout(5000)
            void ping() throws RemoteException;

            /**
             * Notification from client when session is not referenced any more.
             */
            @Asynchronous
            void sessionUnreferenced() throws RemoteException;

            /**
             * Notification from client when explicitly closed.
             */
            @Asynchronous
            @Timeout(1000)
            void closedExplicitly() throws RemoteException;

            /**
             * Notification from client when closed due to an unexpected
             * failure.
             */
            @Asynchronous
            @Timeout(1000)
            void closedOnFailure(String message, Throwable exception) throws RemoteException;
        }
    }

    private class AdminImpl implements Hidden.Admin {
        public Object sessionDequeue(RemoteCompletion<Object> callback) {
            return mSessionExchanger.dequeue(callback);
        }

        public RemoteInfo getRemoteInfo(VersionedIdentifier typeId) throws NoSuchClassException {
            Class remoteType = (Class) typeId.tryRetrieve();
            if (remoteType == null) {
                throw new NoSuchClassException("No Class found for id: " + typeId);
            }
            return RemoteIntrospector.examine(remoteType);
        }

        public RemoteInfo getRemoteInfo(Identifier typeId) throws NoSuchClassException {
            Class remoteType = (Class) typeId.tryRetrieve();
            if (remoteType == null) {
                throw new NoSuchClassException("No Class found for id: " + typeId);
            }
            return RemoteIntrospector.examine(remoteType);
        }

        public String getUnknownClassInfo(String name) {
            Class clazz;
            try {
                clazz = mTypeResolver.resolveClass(name);
            } catch (IOException e) {
                return null;
            } catch (ClassNotFoundException e) {
                return null;
            }

            if (clazz == null) {
                return null;
            }

            long serialVersionUID;
            char type;
            String supers;
            {
                ObjectStreamClass osc = ObjectStreamClass.lookup(clazz);
                if (osc == null) {
                    serialVersionUID = 0;
                    type = 'N';
                    supers = supersFor(clazz, null, (Class[]) null);
                } else {
                    serialVersionUID = osc.getSerialVersionUID();
                    if (Enum.class.isAssignableFrom(clazz)) {
                        type = 'U';
                        supers = supersFor(clazz, Enum.class, Comparable.class, Serializable.class);
                    } else if (Externalizable.class.isAssignableFrom(clazz)) {
                        type = 'E';
                        supers = supersFor(clazz, null, Externalizable.class, Serializable.class);
                    } else { 
                        type = 'S';
                        supers = supersFor(clazz, null, Serializable.class);
                    }
                }
            }

            StringBuilder b = new StringBuilder();
            b.append(serialVersionUID).append(':').append(type);

            if (supers != null) {
                b.append(':');
                b.append(supers);
            }

            return b.toString();
        }

        private String supersFor(Class clazz, Class<?> impliedSuper, Class... impliedInterfaces) {
            StringBuilder b = new StringBuilder();
            
            Class<?> superclass = clazz.getSuperclass();
            if (superclass != null && superclass != Object.class && superclass != impliedSuper) {
                b.append(TypeDesc.forClass(superclass).getDescriptor());
            }

            b.append(':');

            outer: for (Class<?> iface : clazz.getInterfaces()) {
                if (impliedInterfaces != null) {
                    for (Class<?> implied : impliedInterfaces) {
                        if (iface == implied) {
                            continue outer;
                        }
                    }
                }
                b.append(TypeDesc.forClass(iface).getDescriptor());
            }

            return b.length() <= 1 ? null : b.toString();
        }

        /**
         * Note: Compared to the Admin interface, the names of version
         * arguments are swapped. This is because the local and remote
         * endpoints are now swapped.
         */
        public void disposed(VersionedIdentifier[] ids, int[] remoteVersions, int[] localVersions)
        {
            if (ids != null) {
                int waits = 0;
                for (int i=0; i<ids.length; i++) {
                    waits = dispose(ids[i], remoteVersions[i], localVersions[i], waits);
                }
            }
        }

        private int dispose(VersionedIdentifier id, int remoteVersion, int localVersion, int waits)
        {
            if (id.localVersion() != localVersion) {
                // If local version does not match, then stub for remote object
                // was disposed at the same time that remote object was
                // transported to the client. Client will generate a
                // replacement stub, and so object on server side cannot be
                // disposed. When new stub is garbage collected, another
                // disposed message is generated.
                return waits;
            }

            int currentRemoteVersion;
            while ((currentRemoteVersion = id.remoteVersion()) != remoteVersion) {
                if (currentRemoteVersion - remoteVersion > 0) {
                    // Disposed message was for an older stub instance. It is
                    // no longer applicable.
                    return waits;
                }

                // Disposed message arrived too soon. Wait a bit and let
                // version catch up. This case is not expected to occur when
                // synchronous calls are being made against the object.
                // Asynchronous calls don't keep a local reference to the stub
                // after sending a request, and so it can be garbage collected
                // before the request is received.

                if (isClosing()) {
                    break;
                }

                if (waits > 100) {
                    // At least 10 seconds has elapsed, and version has not
                    // caught up. This could be caused by a failed remote call.
                    // Dispose anyhow and let garbage collector resume.

                    // FIXME: Cause might also be a remote object stuck in a
                    // Pipe. When it's eventually read, it won't resolve to a
                    // local object. Disposal retry needs to be scheduled.
                    break;
                }

                try {
                    Thread.sleep(100);
                    waits++;
                } catch (InterruptedException e) {
                    break;
                }
            }

            mSkeletonFactories.remove(id);

            Skeleton skeleton = mSkeletons.remove(id);
            if (skeleton != null) {
                unreferenced(skeleton);
            }

            return waits;
        }

        public void linkDescriptorCache(Remote link) {
            mDescriptorCache.link(link);
        }

        public void linkDescriptorCache(Remote link, @TimeoutParam long timeout, TimeUnit unit) {
            mDescriptorCache.link(link);
        }

        public void ping() {
            // Nothing to do. Caller reads to see if an exception was thrown.
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

    private class BackgroundTask extends ScheduledTask<RuntimeException> {
        // Use this to avoid logging exceptions if failed to send disposed
        // stubs during shutdown of remote session.
        private int mFailedToDisposeCount;

        protected void doRun() {
            // Allow ping check again, if was supressed.
            supressPingUpdater.compareAndSet(StandardSession.this, 1, 0);

            // Send batch of disposed ids to peer.
            try {
                sendDisposedStubs();
                // Success.
                mFailedToDisposeCount = 0;
            } catch (RemoteException e) {
                mFailedToDisposeCount++;
                if (mFailedToDisposeCount > 2 && !isClosing()) {
                    uncaughtException(e);
                }
            }

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
                            channel.disconnect();
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
                    int age = mClockSeconds - pooledChannel.getIdleTimestamp();
                    if (age < DEFAULT_CHANNEL_IDLE_SECONDS) {
                        break;
                    }
                    mChannelPool.remove();
                }
                pooledChannel.discard();
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

    private class Handler implements ChannelAcceptor.Listener {
        public void accepted(Channel channel) {
            mBroker.accept(this);
            InvocationChannel invChan;
            try {
                invChan = toInvocationChannel(channel, true);
            } catch (IOException e) {
                failed(e);
                return;
            }
            readRequest(invChan);
        }

        public void rejected(RejectedException e) {
            // FIXME: handle this better
            // This is just noise.
            //uncaughtException(new IOException("Failure accepting request", e));
        }

        public void failed(IOException e) {
            // This is just noise.
            //uncaughtException(new IOException("Failure accepting request", e));
        }

        public void closed(IOException e) {
            if (!isClosing()) {
                closeOnFailure(e.getMessage(), e);
            }
        }
    }

    static final AtomicReferenceFieldUpdater<InvocationChan, Future>
        timeoutTaskUpdater = AtomicReferenceFieldUpdater.newUpdater
        (InvocationChan.class, Future.class, "mTimeoutTask");

    static final Future<Object> timedOut = Response.complete(null);

    private final class InvocationChan extends AbstractInvocationChannel {
        private final Channel mChannel;

        private volatile int mTimestamp;

        volatile Future<?> mTimeoutTask;

        InvocationChan(Channel channel) throws IOException {
            super(new ResolvingObjectInputStream(channel.getInputStream()),
                  new ReplacingObjectOutputStream(channel.getOutputStream()));
            mChannel = channel;
        }

        /**
         * Copy constructor which use a clean ObjectInputStream.
         */
        InvocationChan(InvocationChan chan) throws IOException {
            super(chan, new ResolvingObjectInputStream(chan.mChannel.getInputStream()));
            mChannel = chan.mChannel;
        }

        public boolean isInputReady() throws IOException {
            return mChannel.isInputReady() || mInvIn.available() > 0;
        }

        public boolean isOutputReady() throws IOException {
            return mChannel.isOutputReady();
        }

        public int setInputBufferSize(int size) {
            return mChannel.setInputBufferSize(size);
        }

        public int setOutputBufferSize(int size) {
            return mChannel.setOutputBufferSize(size);
        }

        public void inputNotify(Listener listener) {
            mChannel.inputNotify(listener);
        }

        public void outputNotify(Listener listener) {
            mChannel.outputNotify(listener);
        }

        public boolean usesSelectNotification() {
            return mChannel.usesSelectNotification();
        }

        public boolean inputResume() {
            return mChannel.inputResume();
        }

        public boolean isResumeSupported() {
            return mChannel.isResumeSupported();
        }

        boolean inputResume(int timeout, TimeUnit unit) throws IOException {
            if (inputResume()) {
                return true;
            }
            // Try to read EOF, with a timeout.
            startTimeout(timeout, unit);
            return read() < 0 && cancelTimeout() && inputResume();
        }

        public boolean outputSuspend() throws IOException {
            mInvOut.doDrain();
            return mChannel.outputSuspend();
        }

        public void register(CloseableGroup<? super Channel> group) {
            mChannel.register(group);
        }

        public boolean isClosed() {
            return mChannel.isClosed();
        }

        public void close() throws IOException {
            final boolean wasOpen = replaceTimeout(null, 0, null) > 0;
            IOException exception = null;

            try {
                // Drain first, to avoid forcing a flush to channel. This
                // allows channel implementation to buffer any remaining
                // packets together.
                mInvOut.doDrain();
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

        public void disconnect() {
            cancelTimeout();
            mChannel.disconnect();
        }

        public Object getLocalAddress() {
            return mChannel.getLocalAddress();
        }

        public Object getRemoteAddress() {
            return mChannel.getRemoteAddress();
        }

        public Remote installRecycler(Recycler recycler) {
            return null;
        }

        public void setRecycleControl(Remote control) {
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
                } catch (RejectedException e) {
                    throw new RejectedException("Unable to schedule timeout", e);
                }
            }
        }

        public boolean cancelTimeout() {
            try {
                return replaceTimeout(null, 0, null) != 0;
            } catch (RejectedException e) {
                // Not expected to happen since no task was provided.
                return false;
            }
        }

        /**
         * @return -1: closed; 0: timed out; 1: replaced
         */
        private int replaceTimeout(TimeoutTask task, long timeout, TimeUnit unit)
            throws RejectedException
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
                if (isClosed()) {
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
        void recycle() {
            try {
                if (isClosing()) {
                    discard();
                } else {
                    getOutputStream().reset();
                    addToPool();
                }
            } catch (Exception e) {
                disconnect();
                // Ignore.
            }
        }

        void inputResumeAndRecycle() {
            if (isClosing()) {
                disconnect();
                return;
            }

            if (inputResume()) {
                // After reading EOF, ObjectInputStream is horked so replace it.
                try {
                    new InvocationChan(this).addToPool();
                } catch (IOException e) {
                    disconnect();
                }
                return;
            }

            if (!isResumeSupported()) {
                disconnect();
                return;
            }

            // Don't block caller while input is drained.
            try {
                mExecutor.execute(new Runnable() {
                    public void run() {
                        try {
                            if (inputResume(1, TimeUnit.SECONDS) && !isClosing()) {
                                new InvocationChan(InvocationChan.this).addToPool();
                                return;
                            }
                        } catch (IOException e) {
                            // Ignore.
                        }
                        // Discard extra server data.
                        disconnect();
                    }
                });
            } catch (RejectedException e) {
                disconnect();
            }
        }

        void discard() {
            try {
                // Flush any lingering methods which used CallMode.EVENTUAL.
                this.flush();
            } catch (IOException e) {
                // Ignore.
            } finally {
                // Don't close, but disconnect idle channel to ensure
                // underlying socket is really closed. Otherwise, it might go
                // into another pool and sit idle. Besides, the remote endpoint
                // responds to the closed channel by disconnecting it in the
                // handleRequests method. This would prevent socket pooling
                // from working anyhow.
                disconnect();
            }
        }

        int getIdleTimestamp() {
            return mTimestamp;
        }

        void addToPool() {
            mTimestamp = mClockSeconds;
            synchronized (mChannelPool) {
                mChannelPool.add(this);
            }
        }

        private class TimeoutTask extends ScheduledTask<RuntimeException> {
            private Future<?> mMyFuture;

            protected synchronized void doRun() {
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
        protected ObjectStreamClass readClassDescriptor()
            throws IOException, ClassNotFoundException
        {
            int type = readByte();
            if (type == 0) {
                ObjectStreamClass desc = super.readClassDescriptor();
                mDescriptorCache.requestReference(desc);
                return desc;
            } else {
                VersionedIdentifier refId = VersionedIdentifier.read((DataInput) this);
                int refVersion = readInt();
                Remote ref = findIdentifiedRemote(refId);
                refId.updateRemoteVersion(refVersion);
                if (ref == null && isClosing()) {
                    throw new ClosedException("Session is closed");
                }
                assert ref != null;
                return mDescriptorCache.toDescriptor(ref);
            }
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
                type = TypeDesc.forClass
                    (mTypeResolver.resolveClass(root.getRootName(), mRemoteAdmin));
                while (--dims >= 0) {
                    type = type.toArrayType();
                }
                return type.toClass();
            } else {
                return mTypeResolver.resolveClass(name, mRemoteAdmin);
            }
        }

        @Override
        protected Object resolveObject(Object obj) throws IOException {
            if (obj instanceof Marshalled) {
                if (obj instanceof MarshalledIntrospectionFailure) {
                    throw ((MarshalledIntrospectionFailure) obj).toException();
                }

                MarshalledRemote mr = (MarshalledRemote) obj;
                VersionedIdentifier objId = mr.mObjId;

                {
                    Remote remote = findIdentifiedRemote(objId);
                    if (remote != null) {
                        mr.updateRemoteVersions();
                        return remote;
                    }
                }

                VersionedIdentifier typeId = mr.mTypeId;
                StubFactory factory = mStubFactories.get(typeId);

                if (factory == null) {
                    RemoteInfo info = mr.mInfo;
                    if (info == null) {
                        info = mRemoteAdmin.getRemoteInfo(typeId);
                    }
                    Class type = mTypeResolver.resolveRemoteType(info);
                    factory = StubFactoryGenerator.getStubFactory(type, info);
                    StubFactory existing = register(mStubFactories, typeId, factory);
                    if (existing == factory) {
                        mStubFactoryRefs.put
                            (typeId, new StubFactoryRef(factory, mReferenceQueue, typeId));
                    } else {
                        // Use existing instance instead.
                        factory = existing;
                    }
                }

                mr.updateRemoteVersions();
                return createAndRegisterStub(objId, factory, new StubSupportImpl(objId));
            }

            return obj;
        }
    }

    private class ReplacingObjectOutputStream extends DrainableObjectOutputStream {
        ReplacingObjectOutputStream(OutputStream out) throws IOException {
            super(out);
            enableReplaceObject(true);
        }

        @Override
        protected void writeStreamHeader() {
            // Do nothing and prevent deadlock when connecting.
        }

        @Override
        protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
            Remote ref = mDescriptorCache.toReference(desc);
            if (ref == null) {
                write(0);
                super.writeClassDescriptor(desc);
            } else {
                write(1);
                // Write like MarshalledRemote, except without actually using
                // one. The type and info of the reference doesn't need to be
                // serialized, because the remote endpoint knows what to expect.
                VersionedIdentifier.identify(ref).writeWithNextVersion(this);
            }
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
                VersionedIdentifier objId = VersionedIdentifier.identify(remote);

                Class remoteType;
                try {
                    remoteType = RemoteIntrospector.getRemoteType(remote);
                } catch (IllegalArgumentException e) {
                    return new MarshalledIntrospectionFailure
                        (e.getMessage(), obj.getClass().getName());
                }

                VersionedIdentifier typeId = VersionedIdentifier.identify(remoteType);
                RemoteInfo info = null;

                if (!mStubRefs.containsKey(objId) && !mSkeletons.containsKey(objId)) {
                    // Create skeleton for use by client. This also prevents
                    // remote object from being freed by garbage collector.

                    SkeletonFactory factory = mSkeletonFactories.get(typeId);
                    if (factory == null) {
                        try {
                            factory = SkeletonFactoryGenerator.getSkeletonFactory(remoteType);
                        } catch (IllegalArgumentException e) {
                            return new MarshalledIntrospectionFailure(e.getMessage(), remoteType);
                        }
                        SkeletonFactory existing = mSkeletonFactories.putIfAbsent(typeId, factory);
                        if (existing != null && existing != factory) {
                            factory = existing;
                        } else {
                            // Only send RemoteInfo for first use of exported
                            // object. If not sent, client will request it anyhow, so
                            // this is an optimization to avoid an extra round trip.
                            info = RemoteIntrospector.examine(remoteType);
                        }
                    }

                    Skeleton skeleton = factory.createSkeleton(objId, mSkeletonSupport, remote);

                    if (!addSkeleton(objId, skeleton)) {
                        throw new ClosedException("Session is closed");
                    }
                }

                obj = new MarshalledRemote(objId, typeId, info);
            }

            return obj;
        }
    }

    private class SkeletonSupportImpl implements SkeletonSupport {
        @Override
        public Pipe requestReply(InvocationChannel channel) {
            return new ServerPipe(channel) {
                @Override
                void tryInputResume(InvocationChannel channel) {
                    finishedAsync(channel, true);
                }
            };
        }

        @Override
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

        @Override
        public <R extends Remote> void linkBatchedRemote(Skeleton skeleton,
                                                         String skeletonMethodName,
                                                         Identifier typeId,
                                                         VersionedIdentifier remoteId,
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

            SkeletonFactory factory = mRemoteSkeletonFactories.get(typeId);
            if (factory == null) {
                RemoteInfo remoteInfo = mRemoteAdmin.getRemoteInfo(typeId);
                factory = SkeletonFactoryGenerator.getSkeletonFactory(type, remoteInfo);

                SkeletonFactory existing = register(mRemoteSkeletonFactories, typeId, factory);
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

            addSkeleton(remoteId, factory.createSkeleton(remoteId, this, remote));
        }

        @Override
        public <R extends Remote> R failedBatchedRemote(Class<R> type, final Throwable cause) {
            InvocationHandler handler = new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    throw cause;
                }
            };

            return (R) Proxy.newProxyInstance
                (getClass().getClassLoader(), new Class[] {type}, handler);
        }

        @Override
        public int finished(InvocationChannel channel, boolean reset) {
            try {
                channel.getOutputStream().flush();
                if (reset) {
                    // Reset after flush to avoid blocking if send buffer is
                    // full. Otherwise, call could deadlock if reader is not
                    // reading the response from the next invocation on this
                    // channel. The reset operation will write data, but it
                    // won't be immediately flushed out of the channel's
                    // buffer. The buffer needs to have at least two bytes to
                    // hold the TC_ENDBLOCKDATA and TC_RESET opcodes.
                    channel.getOutputStream().reset();
                }
                return Skeleton.READ_ANY_THREAD;
            } catch (IOException e) {
                channel.disconnect();
                return Skeleton.READ_FINISHED;
            }
        }

        @Override
        public void finishedAsync(InvocationChannel channel) {
            finishedAsync(channel, false);
        }

        void finishedAsync(InvocationChannel channel, boolean inputResume) {
            try {
                // Let another thread process next request while this thread
                // continues to process active request.
                if (inputResume) {
                    resumeAndReadRequestAsync(channel);
                } else {
                    listenForRequestAsync(channel);
                }
            } catch (RejectedException e) {
                closeDueToRejection(channel, e);
            }
        }

        @Override
        public int finished(InvocationChannel channel, Throwable cause) {
            try {
                channel.getOutputStream().writeThrowable(cause);
                return finished(channel, true);
            } catch (IOException e) {
                channel.disconnect();
                return Skeleton.READ_FINISHED;
            }
        }

        @Override
        public void uncaughtException(Throwable cause) {
            StandardSession.this.uncaughtException(cause);
        }

        @Override
        public OrderedInvoker createOrderedInvoker() {
            return new OrderedInvoker();
        }

        @Override
        public void dispose(VersionedIdentifier objId) {
            Skeleton skeleton = mSkeletons.remove(objId);
            if (skeleton != null) {
                unreferenced(skeleton);
            }
        }
    }

    private class StubSupportImpl extends AbstractStubSupport {
        StubSupportImpl(VersionedIdentifier id) {
            super(id);
        }

        // Used by batched methods that return a remote object.
        private StubSupportImpl() {
            super();
        }

        @Override
        public Link sessionLink() {
            return LinkWrapper.wrap(StandardSession.this);
        }

        @Override
        public InvocationChannel unbatch() {
            InvocationChannel channel = mLocalChannel.get();
            if (channel != null) {
                mLocalChannel.set(null);
            }
            return channel;
        }

        @Override
        public void rebatch(InvocationChannel channel) {
            if (channel != null) {
                if (mLocalChannel.get() != null) {
                    throw new IllegalStateException();
                }
                mLocalChannel.set(channel);
            }
        }

        @Override
        public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx) throws T {
            InvocationChannel channel;
            try {
                channel = getChannel();
            } catch (IOException e) {
                throw failed(remoteFailureEx, null, e);
            }

            try {
                mObjId.writeWithNextVersion(channel.getOutputStream());
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e);
            }

            return channel;
        }

        @Override
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
                mObjId.writeWithNextVersion(channel.getOutputStream());
            } catch (IOException e) {
                throw failedAndCancelTimeout(remoteFailureEx, channel, e, timeout, unit);
            }

            return channel;
        }

        @Override
        public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx,
                                                              double timeout, TimeUnit unit)
            throws T
        {
            if (timeout > 0) {
                // Timeout is positive and not NaN.
            } else {
                if (timeout < 0) {
                    return invoke(remoteFailureEx);
                } else {
                    // Fail immediately if zero timeout.
                    throw failed(remoteFailureEx, null, new RemoteTimeoutException(timeout, unit));
                }
            }

            InvocationChannel channel;
            try {
                channel = getChannel
                    (remoteFailureEx, toNanos(timeout, unit), TimeUnit.NANOSECONDS);
            } catch (Throwable e) {
                Throwable cause = e;
                do {
                    if (cause instanceof RemoteTimeoutException) {
                        throw failed(remoteFailureEx, null,
                                     new RemoteTimeoutException(timeout, unit));
                    }
                    cause = cause.getCause();
                } while (cause != null);

                ThrowUnchecked.fire(e);
                return null;
            }

            try {
                mObjId.writeWithNextVersion(channel.getOutputStream());
            } catch (IOException e) {
                throw failedAndCancelTimeout(remoteFailureEx, channel, e, timeout, unit);
            }

            return channel;
        }

        @Override
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
                support.mObjId.writeWithNextVersion(channel.getOutputStream());
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e);
            }

            return createAndRegisterStub(support.mObjId, factory, support);
        }

        @Override
        public void batched(InvocationChannel channel) {
            holdLocalChannel(channel);
        }
 
        @Override
        public void batchedAndCancelTimeout(InvocationChannel channel) {
            if (channel.cancelTimeout()) {
                holdLocalChannel(channel);
            } else {
                channel.disconnect();
            }
        }

        @Override
        public void release(InvocationChannel channel) {
            releaseLocalChannel();
        }

        @Override
        public Pipe requestReply(InvocationChannel channel) {
            releaseLocalChannel();
            return new ClientPipe(channel) {
                @Override
                void tryInputResume(InvocationChannel channel) {
                    if (!(channel instanceof InvocationChan)) {
                        channel.disconnect();
                    } else {
                        ((InvocationChan) channel).inputResumeAndRecycle();
                    }
                }
            };
        }

        @Override
        public void finished(InvocationChannel channel, boolean reset) {
            releaseLocalChannel();
            if (!reset || reset(channel)) {
                if (channel instanceof InvocationChan) {
                    ((InvocationChan) channel).recycle();
                } else {
                    channel.disconnect();
                }
            }
        }

        @Override
        public void finishedAndCancelTimeout(InvocationChannel channel, boolean reset) {
            releaseLocalChannel();
            if (!reset || reset(channel)) {
                if (channel.cancelTimeout() && channel instanceof InvocationChan) {
                    ((InvocationChan) channel).recycle();
                } else {
                    channel.disconnect();
                }
            }
        }

        private boolean reset(InvocationChannel channel) {
            try {
                channel.reset();
                return true;
            } catch (IOException e) {
                channel.disconnect();
                return false;
            }
        }

        @Override
        public <T extends Throwable> T failed(Class<T> remoteFailureEx,
                                              InvocationChannel channel,
                                              Throwable cause)
        {
            releaseLocalChannel();
            if (channel != null) {
                channel.disconnect();
            }
            return remoteException(remoteFailureEx, cause);
        }

        @Override
        public StubSupport dispose() {
            StubSupport support = new DisposedStubSupport(mObjId);
            clearStub(mObjId);
            return support;
        }

        @Override
        protected void checkCommunication(Throwable cause) {
            StandardSession.this.checkCommunication();
        }

        private InvocationChannel getChannel() throws IOException {
            InvocationChannel channel = getPooledChannel();
            if (channel != null) {
                return channel;
            }
            return toInvocationChannel(mBroker.connect(), false);
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
                        channel = toInvocationChannel(mBroker.connect(), false);
                    } else {
                        long startNanos = System.nanoTime();
                        channel = toInvocationChannel(mBroker.connect(timeout, unit), false);
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

        VersionedIdentifier unreachable() {
            return mStubRefs.remove(mObjId) == null ? null : mObjId;
        }
    }
}
