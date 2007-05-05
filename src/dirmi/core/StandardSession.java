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

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
import dirmi.RemoteTimeoutException;
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
public class StandardSession extends Session {
    /**
     * Returns a ThreadFactory which produces non-daemon threads whose name
     * begins with "Session".
     */
    public static ThreadFactory newSessionThreadFactory() {
        return new SessionThreadFactory();
    }

    private static final int DEFAULT_TIMEOUT_MILLIS = -1;
    private static final int DEFAULT_HEARTBEAT_DELAY_MILLIS = 30000;

    final Broker mBroker;
    final Executor mExecutor;
    final Log mLog;

    // Strong references to Skeletons. Skeletons are created as needed and can
    // be recreated as well. This map just provides quick concurrent access to
    // sharable Skeleton instances.
    final ConcurrentMap<Identifier, Skeleton> mSkeletons;

    // FIXME: Change or chuck this map
    final AtomicIntegerMap<Class> mSkeletonTypeCounts;

    // Strong references to PhantomReferences to Stubs. PhantomReferences need
    // to be strongly reachable or else they will be reclaimed sooner than the
    // referent becomes unreachable. When the referent Stub becomes
    // unreachable, its entry in this map must be removed to reclaim memory.
    final ConcurrentMap<Identifier, StubRef> mStubRefs;

    final ReferenceQueue<Remote> mRefQueue;

    // Remote Admin object.
    final Hidden.Admin mRemoteAdmin;

    // Instant (in millis) for next expected heartbeat. If not received, session closes.
    volatile long mNextExpectedHeartbeat;

    volatile int mTimeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    volatile boolean mClosing;

    final Cleaner mCleaner;

    BlockingQueue<Object> mRemoteServerQueue;
    Object mRemoteServer;

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

        mSkeletons = new ConcurrentHashMap<Identifier, Skeleton>();
        mSkeletonTypeCounts = new AtomicIntegerMap<Class>();

        mStubRefs = new ConcurrentHashMap<Identifier, StubRef>();

        mRefQueue = new ReferenceQueue<Remote>();

        // Transmit our admin information. Do so in a separate thread because
        // remote side cannot accept our connection until it sends its
        // identifier. This strategy avoids instant deadlock.
        class SendAdmin implements Runnable {
            IOException error;
            boolean done;

            public synchronized void run() {
                RemoteOutputStream out = null;
                try {
                    RemoteConnection remoteCon = new RemoteCon(mBroker.connecter().connect());
                    out = remoteCon.getOutputStream();
                    out.writeObject(new AdminImpl());
                    out.close();
                } catch (IOException e) {
                    error = e;
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException e2) {
                            // Don't care.
                        }
                    }
                } finally {
                    done = true;
                    notify();
                }
            }

            synchronized void waitUntilDone() throws IOException {
                while (!done) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
                if (error != null) {
                    throw error;
                }
            }
        }

        SendAdmin sendAdmin = new SendAdmin();
        executor.execute(sendAdmin);

        // Accept connection and get remote identifier.
        RemoteConnection remoteCon = new RemoteCon(mBroker.accepter().accept());
        RemoteInputStream in = remoteCon.getInputStream();
        try {
            mRemoteAdmin = (Hidden.Admin) in.readObject();
        } catch (ClassNotFoundException e) {
            IOException io = new IOException();
            io.initCause(e);
            throw io;
        }
        in.close();

        // Wait for admin object to send.
        sendAdmin.waitUntilDone();

        // Temporary queue for exchanging remote servers.
        mRemoteServerQueue = new ArrayBlockingQueue<Object>(1);

        // Don't start cleaner until after exchange of remote servers.
        // Otherwise, it might hog a thread too soon and deadlock construction.
        mCleaner = new Cleaner();

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

        // Exchange remote servers.
        try {
            mRemoteAdmin.setRemoteServer(server);

            Object remoteServer = mRemoteServerQueue.take();
            if (remoteServer instanceof Null) {
                mRemoteServer = null;
            } else {
                mRemoteServer = remoteServer;
            }
        } catch (InterruptedException e) {
            InterruptedIOException io = new InterruptedIOException();
            io.initCause(e);
            throw io;
        }

        // Don't need this anymore.
        mRemoteServerQueue = null;

        try {
            // Start stub cleaner thread, which also heartbeats.
            mExecutor.execute(mCleaner);
        } catch (RejectedExecutionException e) {
            try {
                close();
            } catch (IOException e2) {
                // Don't care.
            }
            IOException io = new IOException("Unable to start heartbeat thread");
            io.initCause(e);
            throw io;
        }
    }

    public void close() throws IOException {
        if (mClosing) {
            return;
        }
        mClosing = true;

        mCleaner.interrupt();

        if (mExecutor instanceof ExecutorService) {
            try {
                ((ExecutorService) mExecutor).shutdownNow();
            } catch (SecurityException e) {
            }
        }

        try {
            mRemoteAdmin.closed();
            if (mBroker instanceof Closeable) {
                ((Closeable) mBroker).close();
            }
        } finally {
            mSkeletons.clear();
            mSkeletonTypeCounts.clear();
            mStubRefs.clear();
        }
    }

    /*
    public void setTimeoutMillis(int millis) {
        mTimeoutMillis = millis;
    }
    */

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

        Skeleton skeleton = mSkeletons.remove(id);
        if (skeleton != null) {
            /* FIXME: don't use ref counts for Skeleton type.
            Object remote = id.tryRetrieve();
            if (remote != null && remote instanceof Remote) {
                Class remoteType = RemoteIntrospector.getRemoteType((Remote) remote);
                if (mSkeletonTypeCounts.decrementAndGet(remoteType) < 0) {
                    mSkeletonTypeCounts.incrementAndGet(remoteType);
                }
            }
            */
            doNotify = true;
        }

        StubRef ref = mStubRefs.remove(id);
        if (ref != null) {
            ref.getStubSupport().dispose();
            doNotify = true;
        }

        return doNotify;
    }

    void heartbeatReceived() {
        mNextExpectedHeartbeat = System.currentTimeMillis() + DEFAULT_HEARTBEAT_DELAY_MILLIS;
    }

    // Allow null to be placed into blocking queue.
    private static class Null {}

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

    private static class StubRef extends PhantomReference<Remote> {
        private final StubSupportImpl mStubSupport;

        StubRef(Remote stub, ReferenceQueue<? super Remote> queue, StubSupportImpl support) {
            super(stub, queue);
            mStubSupport = support;
        }

        StubSupportImpl getStubSupport() {
            return mStubSupport;
        }

        Identifier getObjectID() {
            return mStubSupport.getObjectID();
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
            void setRemoteServer(Object remote) throws RemoteException, InterruptedException;

            /**
             * Returns RemoteInfo object from server.
             */
            RemoteInfo getRemoteInfo(Identifier id) throws RemoteException;

            /**
             * Notification from client when it has disposed of an identified object.
             */
            @Asynchronous(permits=2)
            void disposed(Identifier id) throws RemoteException;

            /**
             * Notification from client when explicitly closed.
             */
            @Asynchronous
            void closed() throws RemoteException;

            /**
             * Notification from client that it is alive.
             */
            @Asynchronous
            void heartbeat() throws RemoteException;
        }
    }

    private class AdminImpl implements Hidden.Admin {
        public void setRemoteServer(Object server) throws InterruptedException {
            if (server == null) {
                mRemoteServerQueue.put(new Null());
            } else {
                mRemoteServerQueue.put(server);
            }
        }

        public RemoteInfo getRemoteInfo(Identifier id) throws NoSuchClassException {
            Class remoteType = (Class) id.tryRetrieve();
            if (remoteType == null) {
                throw new NoSuchClassException("No Class found for id: " + id);
            }
            return RemoteIntrospector.examine(remoteType);
        }

        public void disposed(Identifier id) {
            dispose(id);
        }

        public void closed() {
            mSkeletons.clear();
            mSkeletonTypeCounts.clear();
            mStubRefs.clear();
            if (mBroker instanceof Closeable) {
                try {
                    ((Closeable) mBroker).close();
                } catch (IOException e) {
                    // Don't care.
                }
            }
        }

        public void heartbeat() {
            heartbeatReceived();
        }
    }

    private class Cleaner implements Runnable {
        private volatile Thread mThread;
        private volatile boolean mInterruptable;

        public void run() {
            mThread = Thread.currentThread();

            long heartbeatDelay = DEFAULT_HEARTBEAT_DELAY_MILLIS / 2;
            heartbeatReceived();

            try {
                while (!mClosing) {
                    Reference<? extends Remote> ref;
                    try {
                        mInterruptable = true;
                        ref = mRefQueue.remove(heartbeatDelay);
                    } finally {
                        mInterruptable = false;
                    }

                    if (!mClosing) {
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

                        // Send our heartbeat to peer.
                        try {
                            mRemoteAdmin.heartbeat();
                        } catch (RemoteException e) {
                            if (!mClosing) {
                                mLog.error("Unable to send heartbeat", e);
                            }
                        }
                    }

                    if (ref == null) {
                        continue;
                    }

                    do {
                        // Stupid Object casts to workaround compiler bug.
                        if (((Object) ref) instanceof StubRef) {
                            Identifier id = ((StubRef) ((Object) ref)).getObjectID();
                            mStubRefs.remove(id);
                            if (!mClosing) {
                                try {
                                    mRemoteAdmin.disposed(id);
                                } catch (RemoteException e) {
                                    if (!mClosing) {
                                        mLog.error("Unable notify remote object disposed", e);
                                    }
                                }
                            }
                        }
                    } while ((ref = mRefQueue.poll()) != null);
                }
            } catch (InterruptedException e) {
                // Assuming we're a pooled thread, clear the flag to prevent
                // problems when this thread gets reused.
                mThread.interrupted();
                if (!mClosing) {
                    mLog.error("Stub cleaner interrupted", e);
                }
            }
        }

        void interrupt() {
            // This implementation has race conditions which can cause the
            // cleaner to not receive the interrupt when it should. This isn't
            // terribly harmful however, as it just causes the cleaner to not
            // exit as promptly.
            if (mInterruptable) {
                Thread t = mThread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }
    }

    private class Accepter implements Runnable {
        public void run() {
            boolean spawned;
            do {
                Connection con;
                try {
                    con = mBroker.accepter().accept();
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
                    mExecutor.execute(new Accepter());
                    spawned = true;
                } catch (RejectedExecutionException e) {
                    mLog.warn("Unable to spawn replacement accept thread; will loop back", e);
                    spawned = false;
                }

                try {
                    final RemoteConnection remoteCon = new RemoteCon(con);

                    // Decide if connection is to be used for invoking method or to
                    // receive an incoming remote object.

                    RemoteInputStream in = remoteCon.getInputStream();
                    Identifier id = Identifier.read(in);

                    // Find a Skeleton to invoke.
                    Skeleton skeleton = mSkeletons.get(id);

                    if (skeleton == null) {
                        // Create the skeleton.
                        Object obj = id.tryRetrieve();
                        if (obj instanceof Remote) {
                            Remote remote = (Remote) obj;
                            Class remoteType = RemoteIntrospector.getRemoteType(remote);
                            SkeletonFactory factory =
                                SkeletonFactoryGenerator.getSkeletonFactory(remoteType);
                            skeleton = factory.createSkeleton(remote);
                            Skeleton existing = mSkeletons.putIfAbsent(id, skeleton);
                            if (existing != null) {
                                skeleton = existing;
                            }
                        } else {
                            Throwable t = new NoSuchObjectException
                                ("Server cannot find remote object: " + id);
                            try {
                                remoteCon.getOutputStream().writeThrowable(t);
                                remoteCon.close();
                            } catch (IOException e) {
                                mLog.error("Failure processing accepted connection. " +
                                           "Server cannot find remote object and " +
                                           "cannot send error to client. Object id: " + id, e);
                            }
                            continue;
                        }
                    }

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
                        mLog.error("Unhandled exception in asynchronous server method", cause);
                        continue;
                    }

                    remoteCon.getOutputStream().writeThrowable(throwable);
                    remoteCon.close();
                } catch (IOException e) {
                    mLog.error("Failure processing accepted connection", e);
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
                                                getLocalAddressString())
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

        public RemoteOutputStream getOutputStream() throws IOException {
            return mRemoteOut;
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
                            mLog.warn("Remote interface not found", e);
                            type = Remote.class;
                        }

                        factory = StubFactoryGenerator.getStubFactory(type, info);
                        factory = (StubFactory) typeID.register(factory);
                    }

                    StubSupportImpl support = new StubSupportImpl(objID);
                    remote = factory.createStub(support);
                    remote = (Remote) objID.register(remote);

                    mStubRefs.put(objID, new StubRef(remote, mRefQueue, support));
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

                // FIXME: Don't use ref counting.
                // Only send RemoteInfo for first use of exported object.
                if (!mStubRefs.containsKey(objID) && !mSkeletons.containsKey(objID)) {
                    if (mSkeletonTypeCounts.getAndIncrement(remoteType) == 0) {
                        // Send info for first use of remote type. If not sent,
                        // client will request it anyhow, so this is an
                        // optimization to avoid an extra round trip.
                        try {
                            info = RemoteIntrospector.examine(remoteType);
                        } catch (IllegalArgumentException e) {
                            mSkeletonTypeCounts.getAndDecrement(remoteType); // undo increment
                            throw new WriteAbortedException("Malformed Remote object", e);
                        }
                    }
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

        public void register(AsynchronousCompletion completion) {
            // FIXME
        }

        public RemoteConnection invoke() throws RemoteException {
            if (mDisposed) {
                throw new NoSuchObjectException("Remote object disposed");
            }
            try {
                int timeoutMillis = mTimeoutMillis;

                RemoteConnection con;
                if (timeoutMillis < 0) {
                    con = new RemoteCon(mBroker.connecter().connect());
                } else {
                    con = new RemoteCon(mBroker.connecter().connect(timeoutMillis));
                }

                mObjID.write(con.getOutputStream());
                return con;
            } catch (RemoteException e) {
                throw e;
            } catch (IOException e) {
                throw new RemoteException(e.getMessage(), e);
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

        void dispose() {
            mDisposed = true;
        }

        Identifier getObjectID() {
            return mObjID;
        }
    }
}
