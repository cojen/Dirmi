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

package dirmi.core2;

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

import dirmi.nio2.StreamBroker;
import dirmi.nio2.StreamChannel;
import dirmi.nio2.StreamListener;
import dirmi.nio2.StreamTask;

import dirmi.core.Identifier;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSession implements Session {
    static final int MAGIC_NUMBER = 0x7696b623;
    static final int PROTOCOL_VERSION = 1;

    private static final int DEFAULT_HEARTBEAT_CHECK_MILLIS = 10000;
    private static final int DEFAULT_CHANNEL_IDLE_MILLIS = 60000;
    private static final int DISPOSE_BATCH = 1000;

    final StreamBroker mBroker;
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

    // Published remote server object.
    final Object mRemoteServer;

    // Remote Admin object.
    final Hidden.Admin mRemoteAdmin;

    // Pool of channels for client calls.
    final LinkedList<InvocationChan> mChannelPool;

    final ScheduledFuture<?> mBackgroundTask;

    volatile long mHeartbeatCheckNanos;

    // Instant (in millis) for next expected heartbeat. If not received, session closes.
    volatile long mNextExpectedHeartbeatMillis;

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
        mLog = log;

        mSkeletonFactories = new ConcurrentHashMap<Identifier, SkeletonFactory>();
        mSkeletons = new ConcurrentHashMap<Identifier, Skeleton>();
        mSkeletonSupport = new SkeletonSupportImpl();
        mStubFactoryRefs = new ConcurrentHashMap<Identifier, StubFactoryRef>();
        mStubRefs = new ConcurrentHashMap<Identifier, StubRef>();
        mDisposedObjects = new ConcurrentLinkedQueue<Identifier>();

        mChannelPool = new LinkedList<InvocationChan>();

        mHeartbeatCheckNanos = TimeUnit.MILLISECONDS.toNanos((DEFAULT_HEARTBEAT_CHECK_MILLIS));

        // Initialize next expected heartbeat.
        heartbeatReceived();

        // Receives magic number and remote object.
        class Bootstrap implements StreamListener {
            private boolean mDone;
            private IOException mIOException;
            private RuntimeException mRuntimeException;
            private Error mError;
            Object mRemoteServer;
            Hidden.Admin mRemoteAdmin;

            public synchronized void established(StreamChannel channel) {
                try {
                    InvocationInputStream in = new InvocationInputStream
                        (new ResolvingObjectInputStream(channel.getInputStream()),
                         channel.getLocalAddress(),
                         channel.getRemoteAddress());

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
                } catch (IOException e) {
                    mIOException = e;
                } catch (RuntimeException e) {
                    mRuntimeException = e;
                } catch (Error e) {
                    mError = e;
                } finally {
                    mDone = true;
                    notifyAll();
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore.
                    }
                }
            }

            public synchronized void failed(IOException e) {
                mIOException = e;
                mDone = true;
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
        broker.accept(bootstrap);

        // Transmit bootstrap information.
        {
            StreamChannel channel = broker.connect();
            try {
                InvocationOutputStream out = new InvocationOutputStream
                    (new ReplacingObjectOutputStream(channel.getOutputStream()),
                     channel.getLocalAddress(),
                     channel.getRemoteAddress());

                out.writeInt(MAGIC_NUMBER);
                out.writeInt(PROTOCOL_VERSION);
                out.writeObject(server);
                out.writeObject(new AdminImpl());
                out.flush();
            } finally {
                channel.close();
            }
        }

        bootstrap.complete();

        mRemoteServer = bootstrap.mRemoteServer;
        mRemoteAdmin = bootstrap.mRemoteAdmin;

        try {
            // Start background task.
            long delay = DEFAULT_HEARTBEAT_CHECK_MILLIS >> 1;
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
        broker.accept(new Handler());
    }

    public void close() throws RemoteException {
        close(true, true, null, null);
    }

    void closeOnFailure(String message, Throwable exception) throws RemoteException {
        close(false, false, message, exception);
    }

    void peerClosed() {
        try {
            close(false, true, null, null);
        } catch (RemoteException e) {
            // Ignore.
        }
    }

    private void close(boolean notify, boolean explicit, String message, Throwable exception)
        throws RemoteException
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

            try {
                mBroker.close();
            } catch (IOException e) {
                throw new RemoteException("Failed to close channel broker", e);
            }
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

    void sendDisposedStubs() throws IOException {
        if (mRemoteAdmin == null) {
            return;
        }

        boolean finished = false;
        do {
            ArrayList<Identifier> disposedStubsList = new ArrayList<Identifier>();
            for (int i = 0; i < DISPOSE_BATCH; i++) {
                Identifier id = mDisposedObjects.poll();
                if (id == null) {
                    finished = true;
                    break;
                }
                disposedStubsList.add(id);
            }

            Identifier[] disposedStubs;

            if (disposedStubsList.size() == 0) {
                disposedStubs = null;
            } else {
                disposedStubs = disposedStubsList
                    .toArray(new Identifier[disposedStubsList.size()]);
            }

            try {
                mRemoteAdmin.disposed(disposedStubs);
            } catch (RemoteException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                if (!mClosing) {
                    error("Unable to dispose remote stubs", e);
                }
            }
        } while (!finished);
    }

    void heartbeatReceived() {
        mNextExpectedHeartbeatMillis = System.currentTimeMillis() +
            TimeUnit.NANOSECONDS.toMillis(mHeartbeatCheckNanos);
    }

    void handleRequest(InvocationChannel invChannel) {
        final Identifier id;
        try {
            id = Identifier.read((DataInput) invChannel.getInputStream());
        } catch (IOException e) {
            try {
                invChannel.close();
            } catch (IOException e2) {
                // Ignore.
            }
            return;
        }

        // Find a Skeleton to invoke.
        Skeleton skeleton = mSkeletons.get(id);

        if (skeleton == null) {
            Throwable t = new NoSuchObjectException("Server cannot find remote object: " + id);
            try {
                invChannel.getOutputStream().writeThrowable(t);
                invChannel.close();
            } catch (IOException e) {
                error("Failure processing request. " +
                      "Server cannot find remote object and " +
                      "cannot send error to client. Object id: " + id, e);
            }
            return;
        }

        try {
            Throwable throwable;

            try {
                try {
                    skeleton.invoke(invChannel);
                } catch (AsynchronousInvocationException e) {
                    throwable = null;
                    Throwable cause = e.getCause();
                    if (cause == null) {
                        cause = e;
                    }
                    warn("Unhandled exception in asynchronous server method", cause);
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
            invChannel.close();
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
             * Notification from client when it has disposed of an identified object.
             */
            @Asynchronous
            void disposed(Identifier id) throws RemoteException;

            /**
             * Notification from client when it has disposed of identified objects.
             */
            @Asynchronous
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

        public void disposed(Identifier id) {
            heartbeatReceived();
            dispose(id);
        }

        public void disposed(Identifier[] ids) {
            heartbeatReceived();
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
            long nowMillis = System.currentTimeMillis();
            if (nowMillis > mNextExpectedHeartbeatMillis) {
                // Didn't get a heartbeat from peer, so close session.
                String message = "No heartbeat received; closing session";
                if (mBroker.isOpen()) {
                    mLog.error(message);
                }
                try {
                    closeOnFailure(message, null);
                } catch (IOException e) {
                    // Ignore.
                }
                return;
            }

            // Send disposed ids to peer, which also serves as a heartbeat.
            try {
                sendDisposedStubs();
            } catch (IOException e) {
                String message = "Unable to send heartbeat; closing session: " + e;
                if (mBroker.isOpen()) {
                    mLog.error(message);
                }
                try {
                    closeOnFailure(message, null);
                } catch (IOException e2) {
                    // Ignore.
                }
                return;
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
            mBroker.accept(this);

            final InvocationChannel invChannel;
            try {
                invChannel = new InvocationChan(channel);
            } catch (IOException e) {
                if (!mClosing) {
                    error("Failure reading request", e);
                }
                try {
                    channel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                return;
            }

            handleRequest(invChannel);
        }

        public void failed(IOException e) {
            String message = "Failure accepting channel; closing session";
            if (mBroker.isOpen()) {
                mLog.error(message, e);
            }
            try {
                closeOnFailure(message, e);
            } catch (IOException e2) {
                // Ignore.
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

        public boolean isOpen() {
            return mChannel.isOpen();
        }

        public InvocationInputStream getInputStream() throws IOException {
            return mInvIn;
        }

        public InvocationOutputStream getOutputStream() throws IOException {
            return mInvOut;
        }

        public void executeWhenReadable(StreamTask task) {
            mChannel.executeWhenReadable(task);
        }

        public void execute(Runnable task) {
            mChannel.execute(task);
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
        public void finished(final InvocationChannel channel) {
            try {
                channel.getOutputStream().reset();
            } catch (IOException e) {
                try {
                    channel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                return;
            }

            channel.executeWhenReadable(new StreamTask() {
                public void run() {
                    handleRequest(channel);
                }

                public void closed() {
                    // Ignore.
                }

                public void closed(IOException e) {
                    // Ignore.
                }
            });
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
