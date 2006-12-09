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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.logging.LogFactory;

import dirmi.Asynchronous;
import dirmi.NoSuchClassException;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;

import dirmi.io.Broker;
import dirmi.io.Connection;
import dirmi.io.Multiplexer;

/**
 * Principal implementation of a RemoteBroker. At least one thread must be
 * calling the Accepter's accept method at all times in order for the
 * RemoteBroker to work.
 *
 * @author Brian S O'Neill
 */
public class PrincipalRemoteBroker extends AbstractRemoteBroker implements Closeable {
    /**
     * Returns a ThreadFactory which produces daemon threads whose name begins
     * with "RemoteBroker".
     */
    public static ThreadFactory newRemoteBrokerThreadFactory() {
        return new RemoteBrokerThreadFactory();
    }

    private static final int DEFAULT_HEARTBEAT_DELAY_MILLIS = 60000;

    final Broker mBroker;
    final Executor mExecutor;

    final ConcurrentMap<Identifier, Skeleton> mSkeletons;
    final ConcurrentMap<Class, AtomicInteger> mSkeletonTypeCounts;

    final ConcurrentMap<Identifier, StubRef> mStubs;

    final ReferenceQueue<Remote> mStubQueue;

    // Identifies our PrincipalRemoteBroker instance.
    final Identifier mLocalID;
    // Identifies remote PrincipalRemoteBroker instance.
    final Identifier mRemoteID;

    // Remote Admin object.
    final Hidden.Admin mRemoteAdmin;

    // Instant (in millis) for next expected heartbeat. If not received, broker closes.
    volatile long mNextExpectedHeartbeat;

    /**
     * @param master single connection which is multiplexed
     */
    public PrincipalRemoteBroker(Connection master) throws IOException {
        this(new Multiplexer(master),
             Executors.newCachedThreadPool(newRemoteBrokerThreadFactory()));
    }

    /**
     * @param master single connection which is multiplexed
     * @param executor used to execute remote methods
     */
    public PrincipalRemoteBroker(Connection master, Executor executor) throws IOException {
        this(new Multiplexer(master), executor);
    }

    /**
     * @param broker connection broker must always connect to same remote server
     * @param executor used to execute remote methods
     */
    public PrincipalRemoteBroker(Broker broker, Executor executor) throws IOException {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }

        mBroker = broker;
        mExecutor = executor;

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

        // FIXME: Start ReferenceQueue thread, which also heartbeats.
    }

    public void close() throws IOException {
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

    protected RemoteConnection connect() throws IOException {
        return connected(mBroker.connecter().connect());
    }

    protected RemoteConnection connect(int timeoutMillis) throws IOException {
        return connected(mBroker.connecter().connect(timeoutMillis));
    }

    private RemoteCon connected(Connection con) throws IOException {
        RemoteCon remoteCon = new RemoteCon(con);
        // Write remote broker ID to indicate that this connection is not to be
        // used for invoking a remote method.
        RemoteOutputStream out = remoteCon.getOutputStream();
        mRemoteID.write(out);
        return remoteCon;
    }

    protected RemoteConnection accept() throws IOException {
        RemoteConnection remoteCon;
        do {
            remoteCon = accepted(mBroker.accepter().accept());
        } while (remoteCon == null);
        return remoteCon;
    }

    protected RemoteConnection accept(int timeoutMillis) throws IOException {
        RemoteConnection remoteCon;
        do {
            remoteCon = accepted(mBroker.accepter().accept(timeoutMillis));
        } while (remoteCon == null);
        return remoteCon;
    }

    /**
     * @return null if connection invoked a remote method
     */
    private RemoteCon accepted(Connection con) throws IOException {
        RemoteCon remoteCon = new RemoteCon(con);

        // Decide if connection is to be used for invoking method or to be
        // returned to accepter.

        RemoteInputStream in = remoteCon.getInputStream();
        Identifier id = Identifier.read(in);

        if (id.equals(mLocalID)) {
            // Magic ID to indicate connection is for accepter.
            return remoteCon;
        }

        // Find a Skeleton to invoke.
        Skeleton skeleton = mSkeletons.get(id);

        if (skeleton == null) {
            Throwable t = new NoSuchObjectException("Server cannot find remote object: " + id);
            remoteCon.getOutputStream().writeThrowable(t);
            remoteCon.close();
        } else {
            // Invoke method in a separate thread.
            mExecutor.execute(new MethodInvoker(remoteCon, skeleton));
        }

        return null;
    }

    void heartbeatReceived() {
        mNextExpectedHeartbeat = System.currentTimeMillis() + DEFAULT_HEARTBEAT_DELAY_MILLIS;
    }

    private static class MethodInvoker implements Runnable {
        private final RemoteConnection mRemoteCon;
        private final Skeleton mSkeleton;

        MethodInvoker(RemoteConnection remoteCon, Skeleton skeleton) {
            mRemoteCon = remoteCon;
            mSkeleton = skeleton;
        }

        public void run() {
            Throwable throwable;

            try {
                try {
                    mSkeleton.invoke(mRemoteCon);
                    return;
                } catch (NoSuchMethodException e) {
                    throwable = e;
                } catch (NoSuchObjectException e) {
                    throwable = e;
                } catch (ClassNotFoundException e) {
                    throwable = e;
                } catch (AsynchronousInvocationException e) {
                    Thread t = Thread.currentThread();
                    t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    return;
                }

                mRemoteCon.getOutputStream().writeThrowable(throwable);
                mRemoteCon.close();
            } catch (IOException e) {
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
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
                            LogFactory.getLog(RemoteBroker.class)
                                .warn("Remote interface not found", e);
                            type = Remote.class;
                        }

                        factory = StubFactoryGenerator.getStubFactory(type, info);
                        factory = (StubFactory) typeID.register(factory);
                    }

                    remote = factory.createStub(new SupportImpl(objID));
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

    private class SupportImpl implements StubSupport {
        private final Identifier mObjID;
        private volatile boolean mDisposed;

        SupportImpl(Identifier id) {
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
            if (support instanceof SupportImpl) {
                return mObjID.equals(((SupportImpl) support).mObjID);
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

    private static class RemoteBrokerThreadFactory implements ThreadFactory {
        static final AtomicInteger mPoolNumber = new AtomicInteger(1);
        final ThreadGroup mGroup;
        final AtomicInteger mThreadNumber = new AtomicInteger(1);
        final String mNamePrefix;

        RemoteBrokerThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            mGroup = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            mNamePrefix = "RemoteBroker-" + mPoolNumber.getAndIncrement() + "-worker-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(mGroup, r, mNamePrefix + mThreadNumber.getAndIncrement(), 0);
            if (!t.isDaemon()) {
                t.setDaemon(true);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }

    private static class StubRef extends PhantomReference<Remote> {
        final Identifier mObjID;

        StubRef(Remote stub, ReferenceQueue<? super Remote> queue, Identifier objID) {
            super(stub, queue);
            mObjID = objID;
        }
    }

    private static class Hidden {
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
