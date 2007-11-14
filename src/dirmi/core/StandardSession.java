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

import java.io.Closeable;
import java.io.Externalizable;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.io.WriteAbortedException;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.ArrayList;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dirmi.Asynchronous;
import dirmi.NoSuchClassException;
import dirmi.Session;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;

import dirmi.io.Broker;
import dirmi.io.Connection;
import dirmi.io.Multiplexer;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSession implements Session {
    /**
     * Returns a ThreadFactory which produces non-daemon threads whose name
     * begins with "Session".
     */
    static ThreadFactory newSessionThreadFactory() {
        return new SessionThreadFactory();
    }

    private static final int DEFAULT_HEARTBEAT_DELAY_MILLIS = 30000;
    private static final int DISPOSE_BATCH = 1000;

    final Broker mBroker;
    final Executor mExecutor;
    final Log mLog;

    // Strong references to SkeletonFactories. SkeletonFactories are created as
    // needed and can be recreated as well. This map just provides quick
    // concurrent access to sharable SkeletonFactory instances.
    final ConcurrentMap<Identifier, SkeletonFactory> mSkeletonFactories;

    // Strong references to Skeletons. Skeletons are created as needed and can
    // be recreated as well. This map just provides quick concurrent access to
    // sharable Skeleton instances.
    final ConcurrentMap<Identifier, Skeleton> mSkeletons;

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

    // Instant (in millis) for next expected heartbeat. If not received, session closes.
    volatile long mNextExpectedHeartbeat;

    volatile boolean mClosing;

    /**
     * @param master single connection which is multiplexed
     */
    public StandardSession(Connection master) throws IOException {
        this(new Multiplexer(master), null, null, null);
    }

    /**
     * @param master single connection which is multiplexed
     * @param server optional server object to export
     */
    public StandardSession(Connection master, Object server) throws IOException {
        this(new Multiplexer(master), server, null, null);
    }

    /**
     * @param master single connection which is multiplexed
     * @param server optional server object to export
     * @param executor used to execute remote methods; pass null for default
     */
    public StandardSession(Connection master, Object server, Executor executor)
        throws IOException
    {
        this(new Multiplexer(master), server, executor, null);
    }

    /**
     * @param broker connection broker must always connect to same remote server
     * @param server optional server object to export
     * @param executor used to execute remote methods; pass null for default
     * @param log message log; pass null for default
     */
    public StandardSession(Broker broker, Object server, Executor executor, Log log)
        throws IOException
    {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (executor == null) {
            executor = Executors.newCachedThreadPool(newSessionThreadFactory());
        }
        if (log == null) {
            log = LogFactory.getLog(Session.class);
        }

        mBroker = broker;
        mExecutor = executor;
        mLog = log;

        mSkeletonFactories = new ConcurrentHashMap<Identifier, SkeletonFactory>();
        mSkeletons = new ConcurrentHashMap<Identifier, Skeleton>();
        mStubFactoryRefs = new ConcurrentHashMap<Identifier, StubFactoryRef>();
        mStubRefs = new ConcurrentHashMap<Identifier, StubRef>();
        mDisposedObjects = new ConcurrentLinkedQueue<Identifier>();

        // Transmit bootstrap information. Do so in a separate thread because
        // remote side cannot accept our connection until it sends its
        // identifier. This strategy avoids instant deadlock.
        Bootstrap bootstrap = new Bootstrap(server, new AdminImpl());
        executor.execute(bootstrap);

        // Accept connection and get remote bootstrap information.
        {
            RemoteConnection remoteCon = new RemoteCon(mBroker.accept());
            RemoteInputStream in = remoteCon.getInputStream();
            try {
                mRemoteServer = in.readObject();
                mRemoteAdmin = (Hidden.Admin) in.readObject();
            } catch (ClassNotFoundException e) {
                IOException io = new IOException();
                io.initCause(e);
                throw io;
            }
            in.close();
        }

        // Wait for bootstrap to complete.
        bootstrap.waitUntilDone();

        // Initialize next expected heartbeat.
        heartbeatReceived();

        try {
            // Start first accept thread.
            mExecutor.execute(new Accepter());
        } catch (RejectedExecutionException e) {
            try {
                close();
            } catch (IOException e2) {
                // Don't care.
            }
            IOException io = new IOException("Unable to start accept thread");
            io.initCause(e);
            throw io;
        }
    }

    public void close() throws RemoteException {
        if (mClosing) {
            return;
        }
        mClosing = true;

        if (mExecutor instanceof ExecutorService) {
            try {
                ((ExecutorService) mExecutor).shutdownNow();
            } catch (SecurityException e) {
            }
        }

        try {
            mRemoteAdmin.closed();
            if (mBroker instanceof Closeable) {
                try {
                    ((Closeable) mBroker).close();
                } catch (IOException e) {
                    throw new RemoteException("Failed to close connection broker", e);
                }
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

    public void dispose(Remote object) throws RemoteException {
        Identifier id = Identifier.identify(object);
        if (dispose(id)) {
            mRemoteAdmin.disposed(id);
        }
    }

    /**
     * @return true if remote side should be notified
     */
    boolean dispose(Identifier id) {
        boolean doNotify = false;

        mSkeletonFactories.remove(id);

        Skeleton skeleton = mSkeletons.remove(id);
        if (skeleton != null) {
            doNotify = true;
        }

        mStubFactoryRefs.remove(id);

        StubRef ref = mStubRefs.remove(id);
        if (ref != null) {
            ref.markStubDisposed();
            doNotify = true;
        }

        return doNotify;
    }


    void sendDisposedStubs() {
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
                if (!mClosing) {
                    error("Unable to dispose remote stubs", e);
                }
            }
        } while (!finished);
    }

    void heartbeatReceived() {
        mNextExpectedHeartbeat = System.currentTimeMillis() + DEFAULT_HEARTBEAT_DELAY_MILLIS;
    }

    void handleRequest(Connection con) {
        final RemoteConnection remoteCon;
        final Identifier id;
        try {
            remoteCon = new RemoteCon(con);
            id = Identifier.read(remoteCon.getInputStream());
        } catch (IOException e) {
            error("Failure reading request", e);
            try {
                con.close();
            } catch (IOException e2) {
                // Don't care.
            }
            return;
        }

        // Find a Skeleton to invoke.
        Skeleton skeleton = mSkeletons.get(id);

        if (skeleton == null) {
            Throwable t = new NoSuchObjectException("Server cannot find remote object: " + id);
            try {
                remoteCon.getOutputStream().writeThrowable(t);
                remoteCon.close();
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
                skeleton.invoke(remoteCon);
                return;
            } catch (NoSuchMethodException e) {
                throwable = e;
            } catch (NoSuchObjectException e) {
                throwable = e;
            } catch (ClassNotFoundException e) {
                throwable = e;
            } catch (AsynchronousInvocationException e) {
                Throwable cause = e.getCause();
                if (cause == null) {
                    cause = e;
                }
                error("Unhandled exception in asynchronous server method", cause);
                return;
            }

            remoteCon.getOutputStream().writeThrowable(throwable);
            remoteCon.close();
        } catch (IOException e) {
            error("Failure processing request", e);
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

    private static class SessionThreadFactory implements ThreadFactory {
        static final AtomicInteger mPoolNumber = new AtomicInteger(1);
        final ThreadGroup mGroup;
        final AtomicInteger mThreadNumber = new AtomicInteger(1);
        final String mNamePrefix;

        SessionThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            mGroup = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            mNamePrefix = "Session-" + mPoolNumber.getAndIncrement() + "-worker-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(mGroup, r, mNamePrefix + mThreadNumber.getAndIncrement(), 0);
            if (!t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
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

        void markStubDisposed() {
            mStubSupport.markDisposed();
        }
    }

    private static class MarshalledRemote implements Externalizable {
        private static final long serialVersionUID = 1;

        Identifier mObjID;
        Identifier mTypeID;
        RemoteInfo mInfo;

        public MarshalledRemote() {
        }

        MarshalledRemote(Identifier objID, Identifier typeID, RemoteInfo info) {
            mObjID = objID;
            mTypeID = typeID;
            mInfo = info;
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            mObjID.write(out);
            mTypeID.write(out);
            out.writeObject(mInfo);
        }

        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            mObjID = Identifier.read(in);
            mTypeID = Identifier.read(in);
            mInfo = (RemoteInfo) in.readObject();
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
            void disposed(Identifier id) throws RemoteException;

            /**
             * Notification from client when it has disposed of identified objects.
             */
            void disposed(Identifier[] ids) throws RemoteException;

            /**
             * Notification from client when explicitly closed.
             */
            @Asynchronous
            void closed() throws RemoteException;
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

        public void closed() {
            clearCollections();
            if (mBroker instanceof Closeable) {
                try {
                    ((Closeable) mBroker).close();
                } catch (IOException e) {
                    // Don't care.
                }
            }
        }
    }

    private class Bootstrap implements Runnable {
        private final Object[] mObjectsToSend;

        private IOException mError;
        private boolean mDone;

        Bootstrap(Object... objectsToSend) {
            mObjectsToSend = objectsToSend;
        }

        public synchronized void run() {
            RemoteOutputStream out = null;
            try {
                RemoteConnection remoteCon = new RemoteCon(mBroker.connect());
                out = remoteCon.getOutputStream();
                for (Object obj : mObjectsToSend) {
                    out.writeObject(obj);
                }
                out.close();
            } catch (IOException e) {
                mError = e;
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e2) {
                        // Don't care.
                    }
                }
            } finally {
                mDone = true;
                notify();
            }
        }

        synchronized void waitUntilDone() throws IOException {
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
        }
    }

    private class Accepter implements Runnable {
        private final int mHeartbeatSendDelay;

        Accepter() {
            this(DEFAULT_HEARTBEAT_DELAY_MILLIS >> 1);
        }

        private Accepter(int heartbeatSendDelay) {
            mHeartbeatSendDelay = heartbeatSendDelay;
        }

        public void run() {
            boolean spawned;
            do {
                long acceptStartNanos = System.nanoTime();
                Connection con;
                try {
                    con = mBroker.tryAccept(mHeartbeatSendDelay);
                } catch (IOException e) {
                    if (!mClosing) {
                        mLog.error("Failure accepting connection; closing session", e);
                        try {
                            close();
                        } catch (IOException e2) {
                            // Don't care.
                        }
                    }
                    return;
                }

                // Spawn a replacement accepter.
                try {
                    Accepter accepter;
                    if (con == null) {
                        accepter = new Accepter();
                    } else {
                        long elapsedNanos = System.nanoTime() - acceptStartNanos;
                        int elapsedMillis = (int) (elapsedNanos / 1000000);
                        accepter = new Accepter(mHeartbeatSendDelay - elapsedMillis);
                    }
                    mExecutor.execute(accepter);
                    spawned = true;
                } catch (RejectedExecutionException e) {
                    warn("Unable to spawn replacement accept thread; will loop back", e);
                    spawned = false;
                }

                if (con != null) {
                    handleRequest(con);
                } else if (!mClosing) {
                    long now = System.currentTimeMillis();
                    if (now > mNextExpectedHeartbeat) {
                        // Didn't get a heartbeat from peer, so close session.
                        mLog.error("No heartbeat received; closing session");
                        try {
                            close();
                        } catch (IOException e) {
                            // Don't care.
                        }
                        return;
                    }

                    // Send disposed ids to peer, which also serves as a heartbeat.
                    sendDisposedStubs();
                }
            } while (!spawned);
        }
    }

    private class RemoteCon implements RemoteConnection {
        private final Connection mCon;
        private final RemoteInputStream mRemoteIn;
        private final RemoteOutputStream mRemoteOut;

        RemoteCon(Connection con) throws IOException {
            mCon = con;
            mRemoteIn = new RemoteInputStream(con.getInputStream(),
                                              getLocalAddressString(),
                                              getRemoteAddressString())
            {
                @Override
                protected ObjectInputStream createObjectInputStream(InputStream in)
                    throws IOException
                {
                    return new ResolvingObjectInputStream(in);
                }
            };

            mRemoteOut = new RemoteOutputStream(con.getOutputStream(),
                                                getLocalAddressString(),
                                                getRemoteAddressString())
            {
                @Override
                protected ObjectOutputStream createObjectOutputStream(OutputStream out)
                    throws IOException
                {
                    return new ReplacingObjectOutputStream(out);
                }
            };
        }

        public void close() throws IOException {
            mCon.close();
        }

        public RemoteInputStream getInputStream() throws IOException {
            return mRemoteIn;
        }

        public int getReadTimeout() throws IOException {
            return mCon.getReadTimeout();
        }

        public void setReadTimeout(int timeoutMillis) throws IOException {
            mCon.setReadTimeout(timeoutMillis);
        }

        public RemoteOutputStream getOutputStream() throws IOException {
            return mRemoteOut;
        }

        public int getWriteTimeout() throws IOException {
            return mCon.getWriteTimeout();
        }

        public void setWriteTimeout(int timeoutMillis) throws IOException {
            mCon.setWriteTimeout(timeoutMillis);
        }

        public String getLocalAddressString() {
            return mCon.getLocalAddressString();
        }

        public String getRemoteAddressString() {
            return mCon.getRemoteAddressString();
        }
    }

    private class ResolvingObjectInputStream extends ObjectInputStream {
        ResolvingObjectInputStream(InputStream out) throws IOException {
            super(out);
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
                            warn("Remote interface not found", e);
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
            if (obj instanceof Remote) {
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
                        factory = SkeletonFactoryGenerator.getSkeletonFactory(remoteType);
                        SkeletonFactory existing = mSkeletonFactories.putIfAbsent(typeID, factory);
                        if (existing != null && existing != factory) {
                            factory = existing;
                        } else {
                            // Only send RemoteInfo for first use of exported
                            // object. If not sent, client will request it anyhow, so
                            // this is an optimization to avoid an extra round trip.
                            try {
                                info = RemoteIntrospector.examine(remoteType);
                            } catch (IllegalArgumentException e) {
                                throw new WriteAbortedException("Malformed Remote object", e);
                            }
                        }
                    }

                    mSkeletons.putIfAbsent(objID, factory.createSkeleton(remote));
                }

                obj = new MarshalledRemote(objID, typeID, info);
            }

            return obj;
        }
    }

    private class StubSupportImpl implements StubSupport {
        private final Identifier mObjID;
        private volatile boolean mDisposed;

        StubSupportImpl(Identifier id) {
            mObjID = id;
        }

        public RemoteConnection invoke() throws RemoteException {
            if (mDisposed) {
                throw new NoSuchObjectException("Remote object disposed");
            }
            try {
                RemoteConnection con = new RemoteCon(mBroker.connect());

                try {
                    mObjID.write(con.getOutputStream());
                } catch (IOException e) {
                    forceConnectionClose(con);
                    throw e;
                }

                return con;
            } catch (RemoteException e) {
                throw e;
            } catch (IOException e) {
                throw new RemoteException(e.getMessage(), e);
            }
        }

        public void recoverServerException(RemoteConnection con) throws Throwable {
            RemoteInputStream in;
            try {
                in = con.getInputStream();
            } catch (IOException e) {
                // Ignore.
                return;
            }
            try {
                in.readOk();
            } catch (RemoteException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    // Ignore.
                    return;
                }
                throw e;
            }
        }

        public void forceConnectionClose(RemoteConnection con) {
            try {
                con.setWriteTimeout(0);
                con.close();
            } catch (IOException e2) {
                // Don't care.
            }
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

        void markDisposed() {
            mDisposed = true;
        }

        void unreachable() {
            mDisposed = true;
            if (mStubRefs.remove(mObjID) != null) {
                mDisposedObjects.add(mObjID);
            }
        }
    }
}
