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
import java.lang.ref.ReferenceQueue;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dirmi.Asynchronous;
import dirmi.AsynchronousInvocationException;
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

    private static final int DEFAULT_HEARTBEAT_DELAY_MILLIS = 60000;

    final Broker mBroker;
    final Executor mExecutor;

    final BlockingQueue<Remote> mQueue;
    final Log mLog;

    final ConcurrentMap<Identifier, Skeleton> mSkeletons;
    final ConcurrentMap<Class, AtomicInteger> mSkeletonTypeCounts;

    final ConcurrentMap<Identifier, StubRef> mStubs;

    final ReferenceQueue<Remote> mStubQueue;

    // Identifies our session instance.
    final Identifier mLocalID;
    // Identifies remote session instance.
    final Identifier mRemoteID;

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
     * @param executor used to execute remote methods; pass null for default
     */
    public StandardSession(Connection master, Executor executor) throws IOException {
        this(new Multiplexer(master), executor, null, null);
    }

    /**
     * @param broker connection broker must always connect to same remote server
     * @param executor used to execute remote methods; pass null for default
     * @param queue queue for received remote objects; pass null for default
     * @param log message log; pass null for default
     */
    public StandardSession(Broker broker,
                           Executor executor,
                           BlockingQueue<Remote> queue,
                           Log log)
        throws IOException
    {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (executor == null) {
            executor = Executors.newCachedThreadPool(newSessionThreadFactory());
        }
        if (queue == null) {
            queue = new SynchronousQueue<Remote>();
        }
        if (log == null) {
            log = LogFactory.getLog(Session.class);
        }

        mBroker = broker;
        mExecutor = executor;
        mQueue = queue;
        mLog = log;

        mSkeletons = new ConcurrentHashMap<Identifier, Skeleton>();
        mSkeletonTypeCounts = new ConcurrentHashMap<Class, AtomicInteger>();

        mStubs = new ConcurrentHashMap<Identifier, StubRef>();

        mStubQueue = new ReferenceQueue<Remote>();

        mLocalID = Identifier.identify(this);

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
                    mLocalID.write(out);
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
        mRemoteID = Identifier.read(in);
        try {
            mRemoteAdmin = (Hidden.Admin) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
        in.close();

        // Wait for our identifier to send.
        sendAdmin.waitUntilDone();

        // Start accept loop.
        mExecutor.execute(new Accepter());

        // FIXME: Start ReferenceQueue thread, which also heartbeats.
    }

    public void close() throws IOException {
        if (mClosing) {
            return;
        }
        mClosing = true;

        // Enqueue special object to unblock caller of receive method.
        mQueue.offer(new Closed());

        if (mExecutor instanceof ExecutorService) {
            try {
                ((ExecutorService) mExecutor).shutdown();
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
            mStubs.clear();
        }
    }

    public void send(Remote remote) throws RemoteException {
        send(remote, 0, false);
    }

    public void send(Remote remote, int timeoutMillis) throws RemoteException {
        send(remote, timeoutMillis, true);
    }

    private void send(Remote remote, int timeoutMillis, boolean timeout) throws RemoteException {
        if (remote == null) {
            throw new IllegalArgumentException();
        }

        try {
            Connection con;
            if (timeout) {
                con = mBroker.connecter().connect(timeoutMillis);
            } else {
                con = mBroker.connecter().connect();
            }

            if (con == null) {
                throw new RemoteTimeoutException();
            }

            RemoteConnection remoteCon = new RemoteCon(con);
            RemoteOutputStream out = remoteCon.getOutputStream();

            // Write remote session ID to indicate that this connection is not
            // to be used for invoking a remote method.
            mRemoteID.write(out);

            out.writeObject(remote);
            out.close();
            remoteCon.close();
        } catch (RemoteException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("Unable to send", e);
        }
    }

    public Remote receive() throws RemoteException {
        return receive(0, false);
    }

    public Remote receive(int timeoutMillis) throws RemoteException {
        return receive(timeoutMillis, true);
    }

    private Remote receive(int timeoutMillis, boolean timeout) throws RemoteException {
        Remote remote;
        try {
            if (timeout) {
                remote = mQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                remote = mQueue.take();
            }
        } catch (InterruptedException e) {
            throw new RemoteException("Receive interrupted", e);
        }

        if (remote == null) {
            throw new RemoteTimeoutException();
        }

        if (remote instanceof Closed) {
            // Enqueue again for future callers.
            mQueue.offer(remote);
            throw new RemoteException("Session is closed");
        }

        return remote;
    }

    public void dispose(Remote object) throws RemoteException {
        // FIXME
    }

    public void dispose(Remote object, int timeoutMillis) throws RemoteException {
        // FIXME
    }

    void heartbeatReceived() {
        mNextExpectedHeartbeat = System.currentTimeMillis() + DEFAULT_HEARTBEAT_DELAY_MILLIS;
    }

    // Special object to put into receive queue when session is closed.
    private static class Closed implements Remote {}

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
             * Notification from client when explicitly closed.
             */
            @Asynchronous
            void closed() throws RemoteException;

            /**
             * Notification from client that it is alive.
             *
             * @return maximum milliseconds to wait before sending next heartbeat
             */
            long heartbeat() throws RemoteException;
        }
    }

    private static class StubRef extends PhantomReference<Remote> {
        final Identifier mObjID;

        StubRef(Remote stub, ReferenceQueue<? super Remote> queue, Identifier objID) {
            super(stub, queue);
            mObjID = objID;
        }
    }

    private static class MarshalledRemote implements Externalizable {
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

    private class Accepter implements Runnable {
        public void run() {
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
            mExecutor.execute(new Accepter());

            try {
                final RemoteConnection remoteCon = new RemoteCon(con);

                // Decide if connection is to be used for invoking method or to
                // receive an incoming remote object.

                RemoteInputStream in = remoteCon.getInputStream();
                Identifier id = Identifier.read(in);

                if (id.equals(mLocalID)) {
                    // Magic ID to indicate connection is for received remove object.
                    try {
                        Remote remote = (Remote) in.readObject();
                        mQueue.put(remote);
                    } catch (InterruptedException e) {
                        mLog.error("Unable to enqueue received remote object", e);
                    } catch (ClassNotFoundException e) {
                        mLog.error("Unable to receive remote object", e);
                    }
                    return;
                }

                // Find a Skeleton to invoke.
                final Skeleton skeleton = mSkeletons.get(id);

                if (skeleton == null) {
                    Throwable t = new NoSuchObjectException
                        ("Server cannot find remote object: " + id);
                    remoteCon.getOutputStream().writeThrowable(t);
                    remoteCon.close();
                    return;
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
                    return;
                }

                remoteCon.getOutputStream().writeThrowable(throwable);
                remoteCon.close();
            } catch (IOException e) {
                mLog.error("Failure processing accepted connection", e);
            }
        }
    }

    private class RemoteCon implements RemoteConnection {
        private final Connection mCon;
        private final RemoteInputStream mRemoteIn;
        private final RemoteOutputStream mRemoteOut;

        RemoteCon(Connection con) throws IOException {
            mCon = con;
            mRemoteIn = new RemoteInputStream(con.getInputStream(), getRemoteAddressString()) {
                @Override
                protected ObjectInputStream createObjectInputStream(InputStream in)
                    throws IOException
                {
                    return new ResolvingObjectInputStream(in);
                }
            };

            mRemoteOut = new RemoteOutputStream(con.getOutputStream(), getLocalAddressString()) {
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

                    remote = factory.createStub(new StubSupportImpl(objID));
                    remote = (Remote) objID.register(remote);

                    mStubs.put(objID, new StubRef(remote, mStubQueue, objID));
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

                // Only send skeleton for first use of exported object.
                Skeleton skeleton;
                if (!mStubs.containsKey(objID) && (skeleton = mSkeletons.get(objID)) == null) {
                    AtomicInteger count = new AtomicInteger(0);
                    AtomicInteger oldCount = mSkeletonTypeCounts.putIfAbsent(remoteType, count);

                    if (oldCount != null) {
                        count = oldCount;
                    }

                    if (count.getAndIncrement() == 0) {
                        // Send info for first use of remote type. If not sent,
                        // client will request it anyhow, so this is an
                        // optimization to avoid an extra round trip.
                        try {
                            info = RemoteIntrospector.examine(remoteType);
                        } catch (IllegalArgumentException e) {
                            count.getAndDecrement(); // undo increment
                            throw new WriteAbortedException("Malformed Remote object", e);
                        }
                    }

                    SkeletonFactory factory =
                        SkeletonFactoryGenerator.getSkeletonFactory(remoteType);

                    skeleton = factory.createSkeleton(remote);
                    mSkeletons.putIfAbsent(objID, skeleton);
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
                RemoteConnection con = new RemoteCon(mBroker.connecter().connect());
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

        public void dispose() throws RemoteException {
            mDisposed = true;
            mRemoteAdmin.disposed(mObjID);
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
            System.out.println("Disposed: " + id);
            Skeleton skeleton = mSkeletons.remove(id);
            if (skeleton != null) {
                System.out.println("Skeleton disposed: " + skeleton);
                Remote remote = skeleton.getRemoteObject();
                Class remoteType = RemoteIntrospector.getRemoteType(remote);
                AtomicInteger count = mSkeletonTypeCounts.get(remoteType);
                System.out.println("Count: " + count);
                if (count != null && count.decrementAndGet() <= 0) {
                    System.out.println("Removed count");
                    mSkeletonTypeCounts.remove(remoteType, count);
                }
            }
        }

        public void closed() {
            mSkeletons.clear();
            mSkeletonTypeCounts.clear();
            mStubs.clear();
            if (mBroker instanceof Closeable) {
                try {
                    ((Closeable) mBroker).close();
                } catch (IOException e) {
                    // Don't care.
                }
            }
        }

        public long heartbeat() {
            heartbeatReceived();
            return DEFAULT_HEARTBEAT_DELAY_MILLIS;
        }
    }
}
