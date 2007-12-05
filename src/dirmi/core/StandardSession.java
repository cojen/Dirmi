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

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.ArrayList;
import java.util.LinkedList;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dirmi.Asynchronous;
import dirmi.NoSuchClassException;
import dirmi.Session;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;

import dirmi.io.Broker;
import dirmi.io.Connection;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSession implements Session {
    static final int MAGIC_NUMBER = 0x7696b623;
    static final int PROTOCOL_VERSION = 1;

    private static final int DEFAULT_HEARTBEAT_DELAY_MILLIS = 30000;
    private static final int DEFAULT_CONNECTION_IDLE_MILLIS = 60000;
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

    // Pool of connections for client calls.
    final LinkedList<InvocationCon> mConnectionPool;

    volatile long mHeartbeatCheckNanos;

    // Instant (in millis) for next expected heartbeat. If not received, session closes.
    volatile long mNextExpectedHeartbeatMillis;

    volatile boolean mClosing;

    /**
     * @param broker connection broker must always connect to same remote server
     * @param server optional server object to export
     */
    public StandardSession(Broker broker, Object server) throws IOException {
        this(broker, server, null, null);
    }

    /**
     * @param broker connection broker must always connect to same remote server
     * @param server optional server object to export
     * @param executor non-shared executor for remote methods; pass null for default
     */
    public StandardSession(Broker broker, Object server, Executor executor)
        throws IOException
    {
        this(broker, server, executor, null);
    }

    /**
     * @param broker connection broker must always connect to same remote server
     * @param server optional server object to export
     * @param executor non-shared executor for remote methods; pass null for default
     * @param log message log; pass null for default
     */
    public StandardSession(Broker broker, Object server, Executor executor, Log log)
        throws IOException
    {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (executor == null) {
            executor = new ThreadPool(Integer.MAX_VALUE, true);
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

        mConnectionPool = new LinkedList<InvocationCon>();

        mHeartbeatCheckNanos = TimeUnit.MILLISECONDS.toNanos((DEFAULT_HEARTBEAT_DELAY_MILLIS));

        // Initialize next expected heartbeat.
        heartbeatReceived();

        // Transmit bootstrap information. Do so in a separate thread because
        // remote side cannot accept our connection until it sends its
        // identifier. This strategy avoids instant deadlock.
        Bootstrap bootstrap = new Bootstrap(server, new AdminImpl());
        executor.execute(bootstrap);

        // Accept connection and get remote bootstrap information.
        InvocationConnection invCon = new InvocationCon(mBroker.accept());

        try {
            // Start first worker thread now to ensure multiplexer is getting
            // processed. This avoids deadlock during bootstrap if send buffer
            // is too small.
            mExecutor.execute(new Worker());
        } catch (RejectedExecutionException e) {
            String message = "Unable to start worker thread";
            try {
                closeOnFailure(message, e);
            } catch (IOException e2) {
                // Don't care.
            }
            IOException io = new IOException(message);
            io.initCause(e);
            throw io;
        }

        InvocationInputStream in = invCon.getInputStream();

        try {
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
            try {
                in.close();
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        } finally {
            in.close();
        }

        // FIXME: re-use initial con for handling requests

        // Wait for bootstrap to complete.
        bootstrap.waitUntilDone();
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
            // Don't care.
        }
    }

    private void close(boolean notify, boolean explicit, String message, Throwable exception)
        throws RemoteException
    {
        if (mClosing) {
            return;
        }
        mClosing = true;

        /*
        if (mExecutor instanceof ExecutorService) {
            try {
                ((ExecutorService) mExecutor).shutdown();
            } catch (SecurityException e) {
            }
        }
        */

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
                                // Don't care.
                            }
                        }
                        throw e;
                    }
                }
            }

            try {
                mBroker.close();
            } catch (IOException e) {
                throw new RemoteException("Failed to close connection broker", e);
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

    /**
     * @return true if connection is still open and can be reused.
     */
    boolean handleRequest(InvocationConnection invCon) {
        final Identifier id;
        try {
            id = Identifier.read((DataInput) invCon.getInputStream());
        } catch (IOException e) {
            try {
                invCon.close();
            } catch (IOException e2) {
                // Don't care.
            }
            try {
                closeOnFailure("Failed to read request identifier", e);
            } catch (IOException e2) {
                // Don't care.
            }
            return false;
        }

        // Find a Skeleton to invoke.
        Skeleton skeleton = mSkeletons.get(id);

        if (skeleton == null) {
            Throwable t = new NoSuchObjectException("Server cannot find remote object: " + id);
            try {
                invCon.getOutputStream().writeThrowable(t);
                invCon.close();
            } catch (IOException e) {
                error("Failure processing request. " +
                      "Server cannot find remote object and " +
                      "cannot send error to client. Object id: " + id, e);
            }
            return false;
        }

        try {
            Throwable throwable;

            try {
                try {
                    return skeleton.invoke(invCon);
                } catch (AsynchronousInvocationException e) {
                    throwable = null;
                    Throwable cause = e.getCause();
                    if (cause == null) {
                        cause = e;
                    }
                    warn("Unhandled exception in asynchronous server method", cause);
                    return false;
                }
            } catch (NoSuchMethodException e) {
                throwable = e;
            } catch (NoSuchObjectException e) {
                throwable = e;
            } catch (ClassNotFoundException e) {
                throwable = e;
            } catch (NotSerializableException e) {
                throwable = e;
            }

            InvocationOutputStream out = invCon.getOutputStream();
            out.writeThrowable(throwable);
            out.flush();
            
            return false;
        } catch (IOException e) {
            error("Failure processing request", e);
            try {
                invCon.close();
            } catch (IOException e2) {
                // Don't care.
            }
            return false;
        }
    }

    InvocationConnection getConnection() throws IOException {
        synchronized (mConnectionPool) {
            if (mConnectionPool.size() > 0) {
                InvocationConnection con = mConnectionPool.removeLast();
                return con;
            }
        }
        return new InvocationCon(mBroker.connect());
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

        void markStubDisposed() {
            mStubSupport.markDisposed();
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

        public void closedExplicitly() {
            peerClosed();
        }

        public void closedOnFailure(String message, Throwable exception) {
            String prefix = "Connection closed by peer due to unexpected failure";
            message = message == null ? prefix : (prefix + ": " + message);
            mLog.error(message, exception);
            peerClosed();
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
            InvocationOutputStream out = null;
            try {
                InvocationConnection invCon = new InvocationCon(mBroker.connect());
                out = invCon.getOutputStream();

                out.writeInt(MAGIC_NUMBER);
                out.writeInt(PROTOCOL_VERSION);

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

            // FIXME: re-use initial con for sending requests
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

    private class Worker implements Runnable {
        // Time when last heatbeat was sent, in nanoseconds.
        private final long mLastHeartbeat;

        Worker() {
            mLastHeartbeat = System.nanoTime();
        }

        private Worker(long lastHeartbeat) {
            mLastHeartbeat = lastHeartbeat;
        }

        public void run() {
            // Copy last heartbeat such that it can be modified in case a new
            // worker couldn't spawn.
            long lastHeartbeat = mLastHeartbeat;

            boolean spawned;
            do {
                long nowNanos = System.nanoTime();
                long delay = lastHeartbeat + (mHeartbeatCheckNanos >> 1) - nowNanos;

                Connection con;
                if (delay <= 0) {
                    // Send a heartbeat instead of accepting a connection.
                    con = null;
                } else {
                    try {
                        con = mBroker.tryAccept(delay, TimeUnit.NANOSECONDS);
                    } catch (IOException e) {
                        if (!mClosing) {
                            String message = "Failure accepting connection; closing session";
                            mLog.error(message, e);
                            try {
                                closeOnFailure(message, e);
                            } catch (IOException e2) {
                                // Don't care.
                            }
                        }
                        return;
                    }
                }

                // Spawn a replacement worker.
                try {
                    Worker worker;
                    if (con == null) {
                        // Heartbeat will be sent, so reset the time.
                        worker = new Worker(lastHeartbeat = nowNanos);
                    } else {
                        worker = new Worker(lastHeartbeat);
                    }
                    mExecutor.execute(worker);
                    spawned = true;
                } catch (RejectedExecutionException e) {
                    warn("Unable to spawn replacement worker thread; will loop back", e);
                    spawned = false;
                }

                if (con != null) {
                    final InvocationConnection invCon;
                    try {
                        invCon = new InvocationCon(con);
                    } catch (IOException e) {
                        if (!mClosing) {
                            error("Failure reading request", e);
                        }
                        try {
                            con.close();
                        } catch (IOException e2) {
                            // Don't care.
                        }
                        return;
                    }

                    while (handleRequest(invCon)) {
                        try {
                            invCon.getOutputStream().reset();
                        } catch (IOException e) {
                            try {
                                invCon.close();
                            } catch (IOException e2) {
                                // Don't care.
                            }
                        }
                    }
                } else if (!mClosing) {
                    long nowMillis = System.currentTimeMillis();
                    if (nowMillis > mNextExpectedHeartbeatMillis) {
                        // Didn't get a heartbeat from peer, so close session.
                        String message = "No heartbeat received; closing session";
                        mLog.error(message);
                        try {
                            closeOnFailure(message, null);
                        } catch (IOException e) {
                            // Don't care.
                        }
                        return;
                    }

                    // Send disposed ids to peer, which also serves as a heartbeat.
                    try {
                        // FIXME: this is sent too often
                        sendDisposedStubs();
                    } catch (IOException e) {
                        String message = "Unable to send heartbeat; closing session: " + e;
                        mLog.error(message);
                        try {
                            closeOnFailure(message, null);
                        } catch (IOException e2) {
                            // Don't care.
                        }
                        return;
                    }

                    // Close idle connections.
                    while (true) {
                        InvocationCon pooledCon;
                        synchronized (mConnectionPool) {
                            pooledCon = mConnectionPool.peek();
                            if (pooledCon == null) {
                                break;
                            }
                            long age = System.currentTimeMillis() - pooledCon.getIdleTimestamp();
                            if (age < DEFAULT_CONNECTION_IDLE_MILLIS) {
                                break;
                            }
                            mConnectionPool.remove();
                        }
                        try {
                            pooledCon.close();
                        } catch (IOException e) {
                            // Don't care.
                        }
                    }
                }
            } while (!spawned);
        }
    }

    private class InvocationCon implements InvocationConnection {
        private final Connection mCon;
        private final InvocationInputStream mInvIn;
        private final InvocationOutputStream mInvOut;

        private volatile long mTimestamp;

        InvocationCon(Connection con) throws IOException {
            mCon = con;
            mInvIn = new InvocationInputStream(con.getInputStream(),
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

            mInvOut = new InvocationOutputStream(con.getOutputStream(),
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

        public InvocationInputStream getInputStream() throws IOException {
            return mInvIn;
        }

        public long getReadTimeout() throws IOException {
            return mCon.getReadTimeout();
        }

        public TimeUnit getReadTimeoutUnit() throws IOException {
            return mCon.getReadTimeoutUnit();
        }

        public void setReadTimeout(long time, TimeUnit unit) throws IOException {
            mCon.setReadTimeout(time, unit);
        }

        public InvocationOutputStream getOutputStream() throws IOException {
            return mInvOut;
        }

        public long getWriteTimeout() throws IOException {
            return mCon.getWriteTimeout();
        }

        public TimeUnit getWriteTimeoutUnit() throws IOException {
            return mCon.getWriteTimeoutUnit();
        }

        public void setWriteTimeout(long time, TimeUnit unit) throws IOException {
            mCon.setWriteTimeout(time, unit);
        }

        public String getLocalAddressString() {
            return mCon.getLocalAddressString();
        }

        public String getRemoteAddressString() {
            return mCon.getRemoteAddressString();
        }

        void recycle() {
            try {
                getOutputStream().reset();
                mTimestamp = System.currentTimeMillis();
                synchronized (mConnectionPool) {
                    mConnectionPool.add(this);
                }
            } catch (Exception e) {
                try {
                    close();
                } catch (IOException e2) {
                    // Don't care.
                }
                // Don't care.
            }
        }

        long getIdleTimestamp() {
            return mTimestamp;
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

        public InvocationConnection invoke() throws RemoteException {
            if (mDisposed) {
                throw new NoSuchObjectException("Remote object disposed");
            }
            InvocationConnection con = null;
            try {
                con = getConnection();
                mObjID.write((DataOutput) con.getOutputStream());
                return con;
            } catch (IOException e) {
                throw failed(con, e);
            }
        }

        public void finished(InvocationConnection con) {
            if (con instanceof InvocationCon) {
                ((InvocationCon) con).recycle();
            }
        }

        public RemoteException failed(InvocationConnection con, Throwable cause) {
            if (con != null) {
                try {
                    con.close();
                } catch (IOException e) {
                    // Don't care.
                }
            }
            if (cause == null) {
                return new RemoteException();
            } else {
                return new RemoteException(cause.getMessage(), cause);
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
