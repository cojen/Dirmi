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

package dirmi.core;

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

import java.lang.reflect.Constructor;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dirmi.Asynchronous;
import dirmi.NoSuchClassException;
import dirmi.RemoteTimeoutException;
import dirmi.Session;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;

import dirmi.io.StreamBroker;
import dirmi.io.StreamChannel;
import dirmi.io.StreamListener;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSession implements Session {
    static final int MAGIC_NUMBER = 0x7696b623;
    static final int PROTOCOL_VERSION = 1;

    private static final int DEFAULT_CHANNEL_IDLE_MILLIS = 60000;
    private static final int DISPOSE_BATCH = 1000;

    final StreamBroker mBroker;
    final ScheduledExecutorService mExecutor;
    final Log mLog;

    // Strong references to SkeletonFactories. SkeletonFactories are created as
    // needed and can be recreated as well. This map just provides quick
    // concurrent access to sharable SkeletonFactory instances.
    final ConcurrentMap<VersionedIdentifier, SkeletonFactory> mSkeletonFactories;

    // Strong references to Skeletons. Skeletons are created as needed and can
    // be recreated as well. This map just provides quick concurrent access to
    // sharable Skeleton instances.
    final ConcurrentMap<VersionedIdentifier, Skeleton> mSkeletons;

    final SkeletonSupport mSkeletonSupport;

    // Strong references to PhantomReferences to StubFactories.
    // PhantomReferences need to be strongly reachable or else they will be
    // reclaimed sooner than the referent becomes unreachable. When the
    // referent StubFactory becomes unreachable, its entry in this map must be
    // removed to reclaim memory.
    final ConcurrentMap<VersionedIdentifier, StubFactoryRef> mStubFactoryRefs;

    // Strong references to PhantomReferences to Stubs. PhantomReferences need
    // to be strongly reachable or else they will be reclaimed sooner than the
    // referent becomes unreachable. When the referent Stub becomes
    // unreachable, its entry in this map must be removed to reclaim memory.
    final ConcurrentMap<VersionedIdentifier, StubRef> mStubRefs;

    // Automatically disposed objects, pending transmission to server.
    final ConcurrentLinkedQueue<VersionedIdentifier> mDisposedObjects;

    final Object mLocalServer;
    final Object mRemoteServer;

    // Remote Admin object.
    final Hidden.Admin mRemoteAdmin;

    // Pool of channels for client calls.
    final LinkedList<InvocationChan> mChannelPool;

    // Thread local channel used with batch calls.
    final ThreadLocal<InvocationChannel> mLocalChannel;

    // Map of channels which are held by threads for batch calls.
    final Map<InvocationChannel, Thread> mHeldChannelMap;

    final ScheduledFuture<?> mBackgroundTask;

    final Object mCloseLock;
    volatile boolean mClosing;

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     * @param server optional server object to export
     */
    public StandardSession(ScheduledExecutorService executor,
                           StreamBroker broker, Object server)
        throws IOException
    {
        this(executor, broker, server, null);
    }

    /**
     * @param executor shared executor for remote methods
     * @param broker channel broker must always connect to same remote server
     * @param server optional server object to export
     * @param log message log; pass null for default
     */
    public StandardSession(ScheduledExecutorService executor,
                           StreamBroker broker, final Object server,
                           Log log)
        throws IOException
    {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }
        if (log == null) {
            log = LogFactory.getLog(Session.class);
        }

        mCloseLock = new Object();

        mBroker = broker;
        mExecutor = executor;
        mLog = log;
        mLocalServer = server;

        mSkeletonFactories = new ConcurrentHashMap<VersionedIdentifier, SkeletonFactory>();
        mSkeletons = new ConcurrentHashMap<VersionedIdentifier, Skeleton>();
        mSkeletonSupport = new SkeletonSupportImpl();
        mStubFactoryRefs = new ConcurrentHashMap<VersionedIdentifier, StubFactoryRef>();
        mStubRefs = new ConcurrentHashMap<VersionedIdentifier, StubRef>();
        mDisposedObjects = new ConcurrentLinkedQueue<VersionedIdentifier>();

        mChannelPool = new LinkedList<InvocationChan>();
        mLocalChannel = new ThreadLocal<InvocationChannel>();
        mHeldChannelMap = Collections.synchronizedMap(new HashMap<InvocationChannel, Thread>());

        // Receives magic number and remote object.
        class Bootstrap implements StreamListener {
            private boolean mDone;
            private IOException mIOException;
            private RuntimeException mRuntimeException;
            private Error mError;
            Object mRemoteServer;
            Hidden.Admin mRemoteAdmin;

            public void established(StreamChannel channel) {
                InvocationChan chan;
                doBoot: synchronized (this) {
                    try {
                        chan = new InvocationChan(channel);
                        InvocationInputStream in = chan.getInputStream();

                        int magic = in.readInt();
                        if (magic != MAGIC_NUMBER) {
                            throw new IOException("Incorrect magic number: " + magic);
                        }

                        int version = in.readInt();
                        if (version != PROTOCOL_VERSION) {
                            throw new IOException("Unsupported protocol version: " + version);
                        }

                        try {
                            mRemoteServer = in.readObject();
                            mRemoteAdmin = (Hidden.Admin) in.readObject();
                        } catch (ClassNotFoundException e) {
                            IOException io = new IOException();
                            io.initCause(e);
                            throw io;
                        }

                        // Reached only upon successful bootstrap.
                        break doBoot;
                    } catch (IOException e) {
                        mIOException = e;
                    } catch (RuntimeException e) {
                        mRuntimeException = e;
                    } catch (Error e) {
                        mError = e;
                    } finally {
                        mDone = true;
                        notifyAll();
                    }

                    if (channel != null) {
                        channel.disconnect();
                    }

                    return;
                }

                // Reuse initial connection for handling incoming requests.
                handleRequest(chan);
            }

            public synchronized void failed(IOException e) {
                mDone = true;
                mIOException = e;
                notifyAll();
            }

            public synchronized void complete() throws IOException {
                while (!mDone) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
                if (mError != null) {
                    throw mError;
                }
                if (mIOException != null) {
                    mIOException.fillInStackTrace();
                    throw mIOException;
                }
                if (mRuntimeException != null) {
                    mRuntimeException.fillInStackTrace();
                    throw mRuntimeException;
                }
            }
        };

        Bootstrap bootstrap = new Bootstrap();
        mBroker.accept(bootstrap);

        // Transmit bootstrap information.
        {
            StreamChannel channel = broker.connect();
            try {
                InvocationChan chan = new InvocationChan(channel);
                InvocationOutputStream out = chan.getOutputStream();

                out.writeInt(MAGIC_NUMBER);
                out.writeInt(PROTOCOL_VERSION);
                out.writeObject(server);
                out.writeObject(new AdminImpl());
                out.flush();

                chan.recycle();
            } catch (IOException e) {
                channel.disconnect();
                throw e;
            }
        }

        bootstrap.complete();

        mRemoteServer = bootstrap.mRemoteServer;
        mRemoteAdmin = bootstrap.mRemoteAdmin;

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

        // Begin accepting requests.
        mBroker.accept(new Handler());
    }

    public void close() throws IOException {
        close(true, true, null, null);
    }

    void closeOnFailure(String message, Throwable exception) throws IOException {
        close(false, false, message, exception);
    }

    void peerClosed() {
        try {
            close(false, true, null, null);
        } catch (IOException e) {
            // Ignore.
        }
    }

    private void close(boolean notify, boolean explicit, String message, Throwable exception)
        throws IOException
    {
        // Use lock as a barrier to prevent race condition with adding entries
        // to mSkeletons.
        synchronized (mCloseLock) {
            if (mClosing) {
                return;
            }
            mClosing = true;
        }

        try {
            try {
                mBackgroundTask.cancel(false);
            } catch (NullPointerException e) {
                if (mBackgroundTask != null) {
                    throw e;
                }
            }

            if (notify && mRemoteAdmin != null) {
                if (explicit) {
                    mRemoteAdmin.closedExplicitly();
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
        }
    }

    // Should only be called by close method.
    private void clearCollections() {
        mSkeletonFactories.clear();
        mStubFactoryRefs.clear();
        mStubRefs.clear();
        mDisposedObjects.clear();

        for (Skeleton skeleton : mSkeletons.values()) {
            unreferenced(skeleton);
        }

        mSkeletons.clear();
    }

    void unreferenced(Skeleton skeleton) {
        try {
            skeleton.unreferenced();
        } catch (Throwable e) {
            error("Remote object unreferenced notification failure", e);
        }
    }

    public Object getRemoteServer() {
        return mRemoteServer;
    }

    public Object getLocalServer() {
        return mLocalServer;
    }

    @Override
    public String toString() {
        return "Session{broker=" + mBroker + '}';
    }

    void sendDisposedStubs() throws IOException {
        if (mRemoteAdmin == null) {
            return;
        }

        while (true) {
            ArrayList<VersionedIdentifier> disposedStubsList =
                new ArrayList<VersionedIdentifier>();
            for (int i = 0; i < DISPOSE_BATCH; i++) {
                VersionedIdentifier id = mDisposedObjects.poll();
                if (id == null) {
                    break;
                }
                disposedStubsList.add(id);
            }

            int size = disposedStubsList.size();
            if (size == 0) {
                return;
            }

            VersionedIdentifier[] disposedStubs = new VersionedIdentifier[size];
            int[] localVersions = new int[size];
            int[] remoteVersions = new int[size];

            for (int i=0; i<size; i++) {
                VersionedIdentifier id = disposedStubsList.get(i);
                disposedStubs[i] = id;
                localVersions[i] = id.localVersion();
                remoteVersions[i] = id.remoteVersion();
            }

            try {
                mRemoteAdmin.disposed(disposedStubs, localVersions, remoteVersions);
            } catch (RemoteException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                if (!mClosing) {
                    error("Unable to dispose remote stubs", e);
                }
            }
        }
    }

    void handleRequest(InvocationChannel invChannel) {
        while (true) {
            final VersionedIdentifier objID;
            final Identifier methodID;
            try {
                DataInput din = (DataInput) invChannel.getInputStream();
                objID = VersionedIdentifier.read(din);
                objID.updateRemoteVersion(invChannel.readInt());
                methodID = Identifier.read(din);
            } catch (IOException e) {
                invChannel.disconnect();
                return;
            }

            // Find a Skeleton to invoke.
            Skeleton skeleton = mSkeletons.get(objID);

            if (skeleton == null) {
                String message = "Cannot find remote object: " + objID;

                error(message);

                boolean synchronous = (methodID.getData() & 0x01) == 0;
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
                invChannel.disconnect();

                return;
            }

            try {
                Throwable throwable;

                try {
                    try {
                        if (skeleton.invoke(methodID, invChannel)) {
                            // Handle another request.
                            continue;
                        }
                    } catch (AsynchronousInvocationException e) {
                        throwable = null;
                        Throwable cause = e.getCause();
                        if (cause == null) {
                            cause = e;
                        }
                        warn("Unhandled exception in asynchronous method", cause);
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
                invChannel.disconnect();
            } catch (IOException e) {
                if (!mClosing) {
                    error("Failure processing request", e);
                }
                invChannel.disconnect();
            }

            return;
        }
    }

    InvocationChannel getChannel() throws IOException {
        InvocationChannel channel = mLocalChannel.get();
        if (channel != null) {
            return channel;
        }

        synchronized (mChannelPool) {
            if (mChannelPool.size() > 0) {
                return mChannelPool.removeLast();
            }
        }

        return new InvocationChan(mBroker.connect());
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

    void warn(String message) {
        mLog.warn(message);
    }

    void warn(String message, Throwable e) {
        mLog.warn(message, e);
    }

    void error(String message) {
        mLog.error(message);
    }

    void error(String message, Throwable e) {
        mLog.error(message, e);
    }

    private class StubFactoryRef extends UnreachableReference<StubFactory> {
        private final VersionedIdentifier mTypeID;

        StubFactoryRef(StubFactory factory, VersionedIdentifier typeID) {
            super(factory);
            mTypeID = typeID;
        }

        protected void unreachable() {
            mDisposedObjects.add(mTypeID);
        }
    }

    private static class StubRef extends UnreachableReference<Remote> {
        private final StubSupportImpl mStubSupport;

        StubRef(Remote stub, StubSupportImpl support) {
            super(stub);
            mStubSupport = support;
        }

        protected void unreachable() {
            mStubSupport.unreachable();
        }
    }

    private static class Hidden {
        // Remote interface must be public, but hide it in a private class.
        public static interface Admin extends Remote {
            /**
             * Returns RemoteInfo object from server.
             */
            RemoteInfo getRemoteInfo(VersionedIdentifier id) throws RemoteException;

            /**
             * Notification from client when it has disposed of identified objects.
             */
            void disposed(VersionedIdentifier[] ids, int[] localVersion, int[] remoteVersions)
                throws RemoteException;

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
        public RemoteInfo getRemoteInfo(VersionedIdentifier id) throws NoSuchClassException {
            Class remoteType = (Class) id.tryRetrieve();
            if (remoteType == null) {
                throw new NoSuchClassException("No Class found for id: " + id);
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

        public void closedExplicitly() {
            peerClosed();
        }

        public void closedOnFailure(String message, Throwable exception) {
            String prefix = "Channel closed by peer due to unexpected failure";
            message = message == null ? prefix : (prefix + ": " + message);
            mLog.error(message, exception);
            peerClosed();
        }
    }

    private class BackgroundTask implements Runnable {
        BackgroundTask() {
        }

        public void run() {
            // Send batch of disposed ids to peer.
            try {
                sendDisposedStubs();
            } catch (IOException e) {
                String message = "Unable to send disposed stubs: " + e;
                if (!mClosing) {
                    mLog.error(message);
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
                    pooledChannel.disconnect();
                }
            }
        }
    }

    private class Handler implements StreamListener {
        public void established(StreamChannel channel) {
            mBroker.accept(this);
            InvocationChannel invChan;
            try {
                invChan = new InvocationChan(channel);
            } catch (IOException e) {
                failed(e);
                return;
            }
            handleRequest(invChan);
        }

        public void failed(IOException e) {
            if (!mClosing) {
                warn("Failure accepting request", e);
                try {
                    closeOnFailure(e.getMessage(), e);
                } catch (IOException e2) {
                    // Ignore.
                }
            }
        }
    }

    private class InvocationChan extends AbstractInvocationChannel {
        private final StreamChannel mChannel;
        private final InvocationInputStream mInvIn;
        private final InvocationOutputStream mInvOut;

        private volatile boolean mClosed;
        private volatile long mTimestamp;

        InvocationChan(StreamChannel channel) throws IOException {
            mChannel = channel;

            mInvOut = new InvocationOutputStream
                (new ReplacingObjectOutputStream(mChannel.getOutputStream()),
                 channel.getLocalAddress(), channel.getRemoteAddress());

            mInvIn = new InvocationInputStream
                (new ResolvingObjectInputStream(mChannel.getInputStream()),
                 channel.getLocalAddress(), channel.getRemoteAddress());
        }

        private InvocationChan(StreamChannel channel,
                               InvocationInputStream in, InvocationOutputStream out)
        {
            mChannel = channel;

            mInvIn = in;
            mInvOut = out;
            mTimestamp = System.currentTimeMillis();
        }

        public InvocationInputStream getInputStream() throws IOException {
            return mInvIn;
        }

        public InvocationOutputStream getOutputStream() throws IOException {
            return mInvOut;
        }

        public final void close() throws IOException {
            mClosed = true;

            OutputStream out = mInvOut;
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    mChannel.disconnect();
                    throw e;
                }
            }

            mChannel.close();
        }

        public final void disconnect() {
            mClosed = true;
            mChannel.disconnect();
        }

        public final Object getLocalAddress() {
            return mChannel.getLocalAddress();
        }

        public final Object getRemoteAddress() {
            return mChannel.getRemoteAddress();
        }

        public final Throwable readThrowable() throws IOException {
            return getInputStream().readThrowable();
        }

        public final void writeThrowable(Throwable t) throws IOException {
            getOutputStream().writeThrowable(t);
        }

        final boolean isClosed() {
            return mClosed;
        }

        /**
         * Recycle the channel to be used for writing new requests.
         */
        final void recycle() {
            try {
                getOutputStream().reset();
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
        protected Class<?> resolveClass(ObjectStreamClass desc)
            throws IOException, ClassNotFoundException
        {
            // FIXME: Need configurable ClassLoader.
            // FIXME: Try to load class from server.
            return super.resolveClass(desc);
        }

        @Override
        protected Object resolveObject(Object obj) throws IOException {
            if (obj instanceof MarshalledRemote) {
                MarshalledRemote mr = (MarshalledRemote) obj;

                VersionedIdentifier objID = mr.mObjID;
                Remote remote = (Remote) objID.tryRetrieve();

                if (remote == null) {
                    VersionedIdentifier typeID = mr.mTypeID;
                    StubFactory factory = (StubFactory) typeID.tryRetrieve();

                    if (factory == null) {
                        RemoteInfo info = mr.mInfo;
                        if (info == null) {
                            info = mRemoteAdmin.getRemoteInfo(typeID);
                        }

                        Class type;
                        try {
                            // FIXME: Use resolveClass.
                            type = Class.forName(info.getName());
                        } catch (ClassNotFoundException e) {
                            warn("Remote interface not found: " + info.getName(), e);
                            type = Remote.class;
                        }

                        factory = typeID.register(StubFactoryGenerator.getStubFactory(type, info));

                        mStubFactoryRefs.put(typeID, new StubFactoryRef(factory, typeID));
                    }

                    StubSupportImpl support = new StubSupportImpl(objID);
                    remote = objID.register(factory.createStub(support));

                    mStubRefs.put(objID, new StubRef(remote, support));
                }

                obj = remote;
            }

            return obj;
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
            if (obj instanceof Remote && !(obj instanceof Serializable)) {
                Remote remote = (Remote) obj;
                VersionedIdentifier objID = VersionedIdentifier.identify(remote);

                Class remoteType;
                try {
                    remoteType = RemoteIntrospector.getRemoteType(remote);
                } catch (IllegalArgumentException e) {
                    throw new WriteAbortedException("Malformed Remote object", e);
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
                            throw new WriteAbortedException("Malformed Remote object", e);
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

                    // Use lock as a barrier to prevent race condition with
                    // close method. Skeletons should not be added to
                    // mSkeletons if they are going to be immediately
                    // unreferenced. Otherwise, there is a possibility that the
                    // unreferenced method is not called.
                    addSkeleton: {
                        synchronized (mCloseLock) {
                            if (!mClosing) {
                                mSkeletons.putIfAbsent(objID, skeleton);
                                break addSkeleton;
                            }
                        }
                        unreferenced(skeleton);
                        throw new RemoteException("Remote session is closing");
                    }
                }

                obj = new MarshalledRemote(objID, typeID, info);
            }

            return obj;
        }
    }

    private class SkeletonSupportImpl implements SkeletonSupport {
        public boolean finished(final InvocationChannel channel, boolean synchronous) {
            if (synchronous) {
                try {
                    channel.getOutputStream().flush();
                    channel.getOutputStream().reset();
                    return true;
                } catch (IOException e) {
                    channel.disconnect();
                    error("Unable to flush response", e);
                    return false;
                }
            } else {
                try {
                    // Let another thread process next request while this
                    // thread continues to process active request.
                    mExecutor.execute(new Runnable() {
                        public void run() {
                            handleRequest(channel);
                        }
                    });
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

        public <T extends Throwable> InvocationChannel prepare(Class<T> remoteFailureEx)
            throws T
        {
            try {
                return getChannel();
            } catch (IOException e) {
                throw failed(remoteFailureEx, null, e);
            }
        }

        public <T extends Throwable> void invoke(Class<T> remoteFailureEx,
                                                 InvocationChannel channel)
            throws T
        {
            try {
                mObjID.write((DataOutput) channel.getOutputStream());
                channel.writeInt(mObjID.nextLocalVersion());
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e);
            }
        }

        public <T extends Throwable> Future<?> invoke(Class<T> remoteFailureEx,
                                                      InvocationChannel channel,
                                                      long timeout, TimeUnit unit)
            throws T
        {
            if (timeout <= 0) {
                if (timeout < 0) {
                    invoke(remoteFailureEx, channel);
                    return null;
                } else {
                    // Fail immediately if zero timeout.
                    throw failed(remoteFailureEx, channel,
                                 new RemoteTimeoutException(timeout, unit));
                }
            }

            Future<?> closeTask;
            try {
                closeTask = mExecutor.schedule(new CloseTask(channel), timeout, unit);
            } catch (RejectedExecutionException e) {
                throw failed(remoteFailureEx, channel, e);
            }

            try {
                mObjID.write((DataOutput) channel.getOutputStream());
                channel.writeInt(mObjID.nextLocalVersion());
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e, timeout, unit, closeTask);
            }

            return closeTask;
        }

        public <T extends Throwable> Future<?> invoke(Class<T> remoteFailureEx,
                                                      InvocationChannel channel,
                                                      double timeout, TimeUnit unit)
            throws T
        {
            if (timeout <= 0) {
                if (timeout < 0) {
                    invoke(remoteFailureEx, channel);
                    return null;
                } else {
                    // Fail immediately if zero timeout.
                    throw failed(remoteFailureEx, channel,
                                 new RemoteTimeoutException(timeout, unit));
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

            Future<?> closeTask;
            try {
                closeTask = mExecutor.schedule
                    (new CloseTask(channel), nanoTimeout, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException e) {
                throw failed(remoteFailureEx, channel, e);
            }

            try {
                mObjID.write((DataOutput) channel.getOutputStream());
                channel.writeInt(mObjID.nextLocalVersion());
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e, timeout, unit, closeTask);
            }

            return closeTask;
        }

        public void batched(InvocationChannel channel) {
            batched(channel, null);
        }
 
        public void batched(InvocationChannel channel, Future<?> closeTask) {
            if (cancelCloseTask(channel, closeTask) && channel instanceof InvocationChan) {
                holdLocalChannel(channel);
            } else {
                try {
                    channel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
            }
        }

        public void finished(InvocationChannel channel) {
            finished(channel, null);
        }

        public void finished(InvocationChannel channel, Future<?> closeTask) {
            releaseLocalChannel();

            if (cancelCloseTask(channel, closeTask) && channel instanceof InvocationChan) {
                ((InvocationChan) channel).recycle();
            } else {
                try {
                    channel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
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
                    ex = new java.rmi.ConnectException(message, (Exception) cause);
                } else if (cause instanceof java.net.UnknownHostException) {
                    ex = new java.rmi.UnknownHostException(message, (Exception) cause);
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

        public <T extends Throwable> T failed(Class<T> remoteFailureEx,
                                              InvocationChannel channel,
                                              Throwable cause,
                                              long timeout, TimeUnit unit, Future<?> closeTask)
        {
            if (!cancelCloseTask(channel, closeTask) && cause instanceof IOException) {
                // Since close task ran, assume cause is timeout.
                cause = new RemoteTimeoutException(timeout, unit);
            }
            return failed(remoteFailureEx, channel, cause);
        }

        public <T extends Throwable> T failed(Class<T> remoteFailureEx,
                                              InvocationChannel channel,
                                              Throwable cause,
                                              double timeout, TimeUnit unit, Future<?> closeTask)
        {
            if (!cancelCloseTask(channel, closeTask) && cause instanceof IOException) {
                // Since close task ran, assume cause is timeout.
                cause = new RemoteTimeoutException(timeout, unit);
            }
            return failed(remoteFailureEx, channel, cause);
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

        void unreachable() {
            if (mStubRefs.remove(mObjID) != null) {
                mDisposedObjects.add(mObjID);
            }
        }

        /**
         * @return true if channel was not closed
         */
        private boolean cancelCloseTask(InvocationChannel channel, Future<?> closeTask) {
            if (closeTask == null) {
                return true;
            }
            if (closeTask.cancel(false)) {
                if (channel instanceof InvocationChan) {
                    // Future interface cannot be queried to determine if task
                    // is currently running. Confirm that channel was not closed.
                    if (((InvocationChan) channel).isClosed()) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
    }

    private static class CloseTask implements Runnable {
        private final InvocationChannel mChannel;

        CloseTask(InvocationChannel channel) {
            mChannel = channel;
        }

        public void run() {
            mChannel.disconnect();
        }
    }
}
