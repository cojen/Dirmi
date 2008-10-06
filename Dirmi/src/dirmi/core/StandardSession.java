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
import java.util.LinkedList;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dirmi.Asynchronous;
import dirmi.NoSuchClassException;
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
    final ConcurrentMap<Identifier, SkeletonFactory> mSkeletonFactories;

    // Strong references to Skeletons. Skeletons are created as needed and can
    // be recreated as well. This map just provides quick concurrent access to
    // sharable Skeleton instances.
    final ConcurrentMap<Identifier, Skeleton> mSkeletons;

    final SkeletonSupport mSkeletonSupport;

    // Strong references to PhantomReferences to StubFactories.
    // PhantomReferences need to be strongly reachable or else they will be
    // reclaimed sooner than the referent becomes unreachable. When the
    // referent StubFactory becomes unreachable, its entry in this map must be
    // removed to reclaim memory.
    final ConcurrentMap<Identifier, StubFactoryRef> mStubFactoryRefs;

    // Strong references to PhantomReferences to Stubs. PhantomReferences need
    // to be strongly reachable or else they will be reclaimed sooner than the
    // referent becomes unreachable. When the referent Stub becomes
    // unreachable, its entry in this map must be removed to reclaim memory.
    final ConcurrentMap<Identifier, StubRef> mStubRefs;

    // Automatically disposed objects, pending transmission to server.
    final ConcurrentLinkedQueue<Identifier> mDisposedObjects;

    final Object mLocalServer;
    final Object mRemoteServer;

    // Remote Admin object.
    final Hidden.Admin mRemoteAdmin;

    // Pool of channels for client calls.
    final LinkedList<InvocationChan> mChannelPool;

    final ScheduledFuture<?> mBackgroundTask;

    volatile boolean mClosing;

    /**
     * @param broker channel broker must always connect to same remote server
     * @param server optional server object to export
     * @param executor shared executor for remote methods
     */
    public StandardSession(StreamBroker broker, Object server,
                           ScheduledExecutorService executor)
        throws IOException
    {
        this(broker, server, executor, null);
    }

    /**
     * @param broker channel broker must always connect to same remote server
     * @param server optional server object to export
     * @param executor shared executor for remote methods
     * @param log message log; pass null for default
     */
    public StandardSession(StreamBroker broker, final Object server,
                           ScheduledExecutorService executor, Log log)
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

        mBroker = broker;
        mExecutor = executor;
        mLog = log;
        mLocalServer = server;

        mSkeletonFactories = new ConcurrentHashMap<Identifier, SkeletonFactory>();
        mSkeletons = new ConcurrentHashMap<Identifier, Skeleton>();
        mSkeletonSupport = new SkeletonSupportImpl();
        mStubFactoryRefs = new ConcurrentHashMap<Identifier, StubFactoryRef>();
        mStubRefs = new ConcurrentHashMap<Identifier, StubRef>();
        mDisposedObjects = new ConcurrentLinkedQueue<Identifier>();

        mChannelPool = new LinkedList<InvocationChan>();

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
                        try {
                            channel.close();
                        } catch (IOException e) {
                            // Ignore.
                        }
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
                try {
                    channel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
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
        if (mClosing) {
            return;
        }
        mClosing = true;

        try {
            mBackgroundTask.cancel(false);
        } catch (NullPointerException e) {
            if (mBackgroundTask != null) {
                throw e;
            }
        }

        try {
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

    void clearCollections() {
        mSkeletonFactories.clear();
        mSkeletons.clear();
        mStubFactoryRefs.clear();
        mStubRefs.clear();
        mDisposedObjects.clear();
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
            ArrayList<Identifier> disposedStubsList = new ArrayList<Identifier>();
            for (int i = 0; i < DISPOSE_BATCH; i++) {
                Identifier id = mDisposedObjects.poll();
                if (id == null) {
                    break;
                }
                disposedStubsList.add(id);
            }

            if (disposedStubsList.size() == 0) {
                return;
            }

            Identifier[] disposedStubs = disposedStubsList
                .toArray(new Identifier[disposedStubsList.size()]);

            try {
                // FIXME: Race condition when disposing of objects which are
                // still required by outstanding asynchronous calls.
                mRemoteAdmin.disposed(disposedStubs);
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
            final Identifier objID;
            final Identifier methodID;
            try {
                DataInput din = (DataInput) invChannel.getInputStream();
                objID = Identifier.read(din);
                methodID = Identifier.read(din);
            } catch (IOException e) {
                try {
                    invChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
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

                try {
                    // Connection must be closed in order to discard any unread
                    // input arguments and piled up asynchronous calls.
                    invChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }

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

                try {
                    // Connection must be closed in order to discard any unread
                    // input arguments and piled up asynchronous calls.
                    invChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
            } catch (IOException e) {
                if (!mClosing) {
                    error("Failure processing request", e);
                }
                try {
                    invChannel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
            }

            return;
        }
    }

    InvocationChannel getChannel() throws IOException {
        synchronized (mChannelPool) {
            if (mChannelPool.size() > 0) {
                InvocationChannel channel = mChannelPool.removeLast();
                return channel;
            }
        }
        return new InvocationChan(mBroker.connect());
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
        private final Identifier mTypeID;

        StubFactoryRef(StubFactory factory, Identifier typeID) {
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
            RemoteInfo getRemoteInfo(Identifier id) throws RemoteException;

            /**
             * Notification from client when it has disposed of identified objects.
             */
            void disposed(Identifier[] ids) throws RemoteException;

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
        public RemoteInfo getRemoteInfo(Identifier id) throws NoSuchClassException {
            Class remoteType = (Class) id.tryRetrieve();
            if (remoteType == null) {
                throw new NoSuchClassException("No Class found for id: " + id);
            }
            return RemoteIntrospector.examine(remoteType);
        }

        public void disposed(Identifier[] ids) {
            if (ids != null) {
                for (Identifier id : ids) {
                    dispose(id);
                }
            }
        }

        private void dispose(Identifier id) {
            mSkeletonFactories.remove(id);
            mSkeletons.remove(id);
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
                    pooledChannel.close();
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }
    }

    private class Handler implements StreamListener {
        public void established(StreamChannel channel) {
            try {
                mBroker.accept(this);
                InvocationChannel invChannel = new InvocationChan(channel);
                handleRequest(invChannel);
            } catch (IOException e) {
                if (!mClosing) {
                    error("Failure reading request", e);
                }
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException e2) {
                        // Ignore.
                    }
                }
            }
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

        private volatile long mTimestamp;

        InvocationChan(StreamChannel channel) throws IOException {
            mChannel = channel;

            mInvOut = new InvocationOutputStream
                (new ReplacingObjectOutputStream(channel.getOutputStream()),
                 getLocalAddress(),
                 getRemoteAddress());

            // Hack to ensure ObjectOutputStream header is sent, to prevent
            // peer from locking up when it constructs ObjectInputStream.
            mInvOut.flush();

            mInvIn = new InvocationInputStream
                (new ResolvingObjectInputStream(channel.getInputStream()),
                 getLocalAddress(),
                 getRemoteAddress());
        }

        public void close() throws IOException {
            mInvOut.close();
            mChannel.close();
        }

        public InvocationInputStream getInputStream() throws IOException {
            return mInvIn;
        }

        public InvocationOutputStream getOutputStream() throws IOException {
            return mInvOut;
        }

        public Object getLocalAddress() {
            return mChannel.getLocalAddress();
        }

        public Object getRemoteAddress() {
            return mChannel.getRemoteAddress();
        }

        public Throwable readThrowable() throws IOException {
            return mInvIn.readThrowable();
        }

        public void writeThrowable(Throwable t) throws IOException {
            mInvOut.writeThrowable(t);
        }

        /**
         * Recycle the channel to be used for writing new requests.
         */
        void recycle() {
            try {
                getOutputStream().reset();
                mTimestamp = System.currentTimeMillis();
                synchronized (mChannelPool) {
                    mChannelPool.add(this);
                }
            } catch (Exception e) {
                try {
                    close();
                } catch (IOException e2) {
                    // Ignore.
                }
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

                Identifier objID = mr.mObjID;
                Remote remote = (Remote) objID.tryRetrieve();

                if (remote == null) {
                    Identifier typeID = mr.mTypeID;
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
        protected Object replaceObject(Object obj) throws IOException {
            if (obj instanceof Remote && !(obj instanceof Serializable)) {
                Remote remote = (Remote) obj;
                Identifier objID = Identifier.identify(remote);

                Class remoteType;
                try {
                    remoteType = RemoteIntrospector.getRemoteType(remote);
                } catch (IllegalArgumentException e) {
                    throw new WriteAbortedException("Malformed Remote object", e);
                }

                Identifier typeID = Identifier.identify(remoteType);
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

                    mSkeletons.putIfAbsent
                        (objID, factory.createSkeleton(mSkeletonSupport, remote));
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
                    try {
                        channel.close();
                    } catch (IOException e2) {
                        // Ignore.
                    }
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
        private final Identifier mObjID;

        StubSupportImpl(Identifier id) {
            mObjID = id;
        }

        public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx)
            throws T
        {
            InvocationChannel channel = null;
            try {
                channel = getChannel();
                mObjID.write((DataOutput) channel.getOutputStream());
                return channel;
            } catch (IOException e) {
                throw failed(remoteFailureEx, channel, e);
            }
        }

        public void finished(InvocationChannel channel) {
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

        public <T extends Throwable> T failed(Class<T> remoteFailureEx,
                                              InvocationChannel channel, Throwable cause)
        {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    // Ignore.
                }
            }

            RemoteException ex;
            if (cause == null) {
                ex = new RemoteException();
            } else {
                String message = cause.getMessage();
                if (message == null || (message = message.trim()).length() == 0) {
                    message = cause.toString();
                }
                ex = new RemoteException(message, cause);
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
    }
}
