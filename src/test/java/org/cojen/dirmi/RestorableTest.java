/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi;

import java.io.Closeable;
import java.io.IOException;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;

import java.util.function.BiPredicate;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.MethodMaker;

import org.cojen.dirmi.core.CoreUtils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RestorableTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RestorableTest.class.getName());
    }

    private Environment mEnv;
    private ServerSocket mServerSocket;
    private Acceptor mAcceptor;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mEnv = Environment.create();
        mEnv.export("main", new R1Server());
        mServerSocket = new ServerSocket(0);
        mAcceptor = new Acceptor(mEnv, mServerSocket);
        mEnv.execute(mAcceptor);
        mEnv.reconnectDelayMillis(100);
        mEnv.pingTimeoutMillis(1000);

        mSession = mEnv.connect(R1.class, "main", "localhost", mServerSocket.getLocalPort());
    }

    @After
    public void teardown() throws Exception {
        if (mAcceptor != null) {
            mAcceptor.close();
        }
        mEnv.close();
    }

    @Test
    public void basic() throws Exception {
        assertEquals(Session.State.CONNECTED, mSession.state());

        R1 root = mSession.root();
        R2 r2 = root.a(123);

        try {
            root.b(999).a();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("non-restorable parent"));
        }

        var listener = new BiPredicate<Session<?>, Throwable>() {
            final List<Session.State> states = new ArrayList<>();

            @Override
            public boolean test(Session<?> session, Throwable ex) {
                if (ex == null) {
                    states.add(session.state());
                }
                return true;
            }
        };

        mSession.addStateListener(listener);

        R1 r1x = r2.a();

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        int disconnected = 0;
        boolean resumed = false;

        while (true) {
            boolean anyFailures = false;

            try {
                root.a(123);
            } catch (DisconnectedException e) {
                disconnected++;
                anyFailures = true;
            } catch (RemoteException e) {
                anyFailures = true;
            }

            try {
                assertEquals("123:789", r2.b(789));
            } catch (DisconnectedException e) {
                disconnected++;
                anyFailures = true;
            } catch (RemoteException e) {
                anyFailures = true;
            }

            try {
                assertEquals(111, r1x.b(111).c());
            } catch (DisconnectedException e) {
                disconnected++;
                anyFailures = true;
            } catch (RemoteException e) {
                anyFailures = true;
            }

            Thread.sleep(1000);

            if (!resumed) {
                if (disconnected > 0) {
                    mAcceptor.resume();
                    resumed = true;
                }
            } else {
                if (!anyFailures) {
                    break;
                }
            }
        }

        assertEquals(List.of(Session.State.CONNECTED,
                             Session.State.DISCONNECTED, Session.State.RECONNECTING,
                             Session.State.RECONNECTED, Session.State.CONNECTED),
                     listener.states);

        listener.states.clear();

        mSession.close();

        assertEquals(List.of(Session.State.CLOSED), listener.states);
    }

    @Test
    public void dispose() throws Exception {
        // A disposed object cannot be restored, and neither can any objects that depend on it
        // for restoration.

        R1 root = mSession.root();
        R2 r2 = root.a(123);
        R1 a = r2.a();
        Session.dispose(r2);

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        int disconnected = 0;
        boolean resumed = false;
        boolean disposed;

        while (true) {
            boolean anyFailures = false;
            disposed = false;

            try {
                root.a(123);
            } catch (DisconnectedException e) {
                disconnected++;
                anyFailures = true;
            } catch (RemoteException e) {
                anyFailures = true;
            }

            try {
                assertEquals("123:789", r2.b(789));
            } catch (DisconnectedException e) {
                disconnected++;
                anyFailures = true;
            } catch (DisposedException e) {
                assertTrue(e.getMessage().contains("disposed"));
                disposed = true;
            } catch (RemoteException e) {
                anyFailures = true;
            }

            Thread.sleep(1000);

            if (!resumed) {
                if (disconnected > 0) {
                    mAcceptor.resume();
                    resumed = true;
                }
            } else {
                if (!anyFailures) {
                    break;
                }
            }
        }

        assertTrue(disposed);

        for (int i=1; i<=2; i++) {
            try {
                a.echo("hello");
                fail();
            } catch (DisposedException e) {
                assertTrue(e.getMessage().contains("origin"));
            }
        }

        assertFalse(Session.dispose(a));

        mSession.close();
    }

    @Test
    public void disposeRoot() throws Exception {
        // A disposed root object cannot be restored, and so the session won't reconnect.

        R1 root = mSession.root();
        Session.dispose(root);

        assertEquals(Session.State.CONNECTED, mSession.state());

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        for (int i=0; i<10; i++) {
            var state = mSession.state();
            if (state == Session.State.CLOSED) {
                break;
            }
            assertTrue(state == Session.State.CONNECTED);
            Thread.sleep(1000);
        }

        assertEquals(Session.State.CLOSED, mSession.state());

        try {
            root.echo("hello");
        } catch (DisposedException e) {
            assertTrue(e.getMessage().contains("disposed"));
        }

        mSession.close();
    }


    @Test
    public void reconnectNotification() throws Exception {
        var listener = new BiPredicate<Session<?>, Throwable>() {
            volatile Throwable exception;

            @Override
            public boolean test(Session<?> session, Throwable ex) {
                exception = ex;
                return true;
            }
        };

        mSession.addStateListener(listener);

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        R1 root = mSession.root();

        Throwable ex = null;

        for (int i=0; i<100; i++) {
            try {
                root.echo("hello");
            } catch (RemoteException e) {
            }

            ex = listener.exception;

            if (ex != null) {
                break;
            }

            Thread.sleep(100);
        }

        assertTrue(ex instanceof RemoteException);
        assertTrue(ex.getMessage().contains("Timed out"));

        mAcceptor.resume();

        check: {
            for (int i=0; i<100; i++) {
                try {
                    assertEquals("hello", root.echo("hello"));
                    break check;
                } catch (RemoteException e) {
                }
                Thread.sleep(100);
            }

            fail("Didn't reconnect");
        }

        assertNull(listener.exception);
    }

    @Test
    public void interfaceChange() throws Exception {
        R1 root = mSession.root();
        R2 r2 = root.a(123);

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        while (true) {
            try {
                r2.c();
                fail();
            } catch (DisconnectedException e) {
                break;
            } catch (RemoteException e) {
                Thread.sleep(100);
            }
        }

        // Define new remote interfaces, with some slight changes.
        changeRemoteInterfaces(true);

        mAcceptor.resume();

        for (int i=0; i<100; i++) {
            try {
                assertEquals(123, r2.c());
                break;
            } catch (DisconnectedException e) {
            }
            Thread.sleep(100);
        }

        assertEquals("123->999", r2.b(999));

        try {
            r2.a();
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("remote side"));
        }

        try {
            // New method can't be found because r2 instance is the same.
            r2.getClass().getMethod("d", String.class);
            fail();
        } catch (NoSuchMethodException e) {
        }

        R2 newR2 = root.a(345);

        try {
            newR2.a();
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("remote side"));
        }

        assertEquals("345->999", newR2.b(999));
        assertEquals(345, newR2.c());
        assertEquals(100, newR2.getClass().getMethod("d", String.class).invoke(newR2, "100"));

        try {
            root.b(1);
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("remote side"));
        }
    }

    private void changeRemoteInterfaces(boolean withAz) throws Exception {
        var loader = new Loader();

        var cm1 = ClassMaker.beginExternal(R1.class.getName())
            .public_().interface_().implement(Remote.class);
        var cm2 = ClassMaker.beginExternal(R2.class.getName())
            .public_().interface_().implement(Remote.class);

        // Drop method "b" for the new R1 and add method "Z".
        cm1.addAnnotation(RemoteFailure.class, true).put("declared", false);
        cm1.addMethod(cm2, "a", int.class).public_().abstract_()
            .addAnnotation(Restorable.class, true);
        if (withAz) {
            cm1.addMethod(cm2, "az", int.class).public_().abstract_()
                .addAnnotation(Restorable.class, true);
        }
        cm1.addMethod(int.class, "Z").public_().abstract_();

        // Drop method "a" for the new R2 and add method "d".
        cm2.addAnnotation(RemoteFailure.class, true).put("declared", false);
        cm2.addMethod(String.class, "b", int.class).public_().abstract_();
        cm2.addMethod(int.class, "c").public_().abstract_();
        cm2.addMethod(int.class, "d", String.class).public_().abstract_();

        Class<?> face1 = loader.finishLocal(R1.class.getName(), cm1);
        Class<?> face2 = loader.finishLocal(R2.class.getName(), cm2);

        String name1 = R1.class.getName() + "$$$";
        String name2 = R2.class.getName() + "$$$";

        cm2 = ClassMaker.beginExternal(name2).public_().implement(face2);
        cm2.addField(int.class, "param").final_();
        MethodMaker mm = cm2.addConstructor(int.class).public_();
        mm.invokeSuperConstructor();
        mm.field("param").set(mm.param(0));

        mm = cm2.addMethod(String.class, "b", int.class).public_().override();
        mm.return_(mm.concat(mm.field("param"), "->", mm.param(0)));
        mm = cm2.addMethod(int.class, "c").public_().override();
        mm.return_(mm.field("param"));
        mm = cm2.addMethod(int.class, "d", String.class).public_().override();
        mm.return_(mm.var(Integer.class).invoke("parseInt", mm.param(0)));

        cm1 = ClassMaker.beginExternal(name1).public_().implement(face1);
        cm1.addConstructor().public_();

        mm = cm1.addMethod(face2, "a", int.class).public_().override();
        mm.return_(mm.new_(cm2, mm.param(0)));

        if (withAz) {
            mm = cm1.addMethod(face2, "az", int.class).public_().override();
            mm.return_(mm.new_(cm2, mm.param(0)));
        }

        cm1.addMethod(int.class, "Z").public_().override().return_(888);

        Class<?> rem1 = loader.finishLocal(name1, cm1);
        Class<?> rem2 = loader.finishLocal(name2, cm2);

        Object obj1 = rem1.getConstructor().newInstance();
        assertNotNull(mEnv.export("main", obj1));
    }

    @Test
    public void interfaceReplace() throws Exception {
        // Define a completely new remote interface.
        interfaceReplace(false);
    }

    @Test
    public void interfaceReplaceDropR2() throws Exception {
        // Define a completely new remote interface and also drop R2 from existence.
        interfaceReplace(true);
    }

    private void interfaceReplace(boolean dropR2) throws Exception {
        R1 root = mSession.root();
        R2 r2 = root.a(123);

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        while (true) {
            try {
                r2.c();
                fail();
            } catch (DisconnectedException e) {
                break;
            } catch (RemoteException e) {
                Thread.sleep(100);
            }
        }

        // Define a completely new remote interface.

        var loader = new Loader();

        if (dropR2) {
            loader.drop(R2.class.getName());
        }

        var cm1 = ClassMaker.beginExternal(R1.class.getName())
            .public_().interface_().implement(Remote.class);

        cm1.addAnnotation(RemoteFailure.class, true).put("declared", false);
        cm1.addMethod(int.class, "q", int.class).public_().abstract_();

        Class<?> face1 = loader.finishLocal(R1.class.getName(), cm1);

        String name1 = R1.class.getName() + "$$$";

        cm1 = ClassMaker.beginExternal(name1).public_().implement(face1);
        cm1.addConstructor().public_();

        MethodMaker mm = cm1.addMethod(int.class, "q", int.class).public_().override();
        mm.return_(mm.param(0).neg());

        Class<?> rem1 = loader.finishLocal(name1, cm1);

        Object obj1 = rem1.getConstructor().newInstance();
        assertNotNull(mEnv.export("main", obj1));

        mAcceptor.resume();

        for (int i=0; i<100; i++) {
            try {
                r2.c();
                fail();
            } catch (DisconnectedException e) {
            } catch (UnimplementedException e) {
                assertTrue(e.getMessage().contains("remote side"));
                break;
            }
            Thread.sleep(100);
        }

        try {
            r2.b(999);
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("remote side"));
        }

        try {
            r2.a();
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("remote side"));
        }

        try {
            root.a(345);
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("remote side"));
        }

        try {
            root.b(1);
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("remote side"));
        }
    }

    @Test
    public void lenient() throws Exception {
        lenient(false);
    }

    @Test
    public void lenientWithKnownRemoteType() throws Exception {
        lenient(true);
    }

    private void lenient(boolean withKnownType) throws Exception {
        R2 r2 = withKnownType ? mSession.root().az(10) : null;

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        r2 = mSession.root().az(10);

        try {
            r2.b(123);
            fail();
        } catch (DisposedException e) {
            assertTrue(e.getMessage().contains("disconnect"));
        }

        mAcceptor.resume();

        check: {
            for (int i=0; i<100; i++) {
                try {
                    assertEquals("10:123", r2.b(123));
                    break check;
                } catch (RemoteException e) {
                }
                Thread.sleep(100);
            }

            // FIXME: Sometimes fails (when withKnownType is true).
            fail("Didn't reconnect");
        }

        r2 = mSession.root().az(20);
        assertEquals("20:123", r2.b(123));
    }

    @Test
    public void lenientChain() throws Exception {
        lenientChain(false);
    }

    @Test
    public void lenientChainFlipped() throws Exception {
        lenientChain(true);
    }

    private void lenientChain(boolean flippedRestore) throws Exception {
        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        R2 r2 = mSession.root().az(10);
        R1 r1 = r2.az();

        try {
            r2.b(123);
            fail();
        } catch (DisposedException e) {
            assertTrue(e.getMessage().contains("disconnect"));
        }

        try {
            r1.echo("hello");
            fail();
        } catch (DisposedException e) {
            assertTrue(e.getMessage().contains("disconnect"));
        }

        mAcceptor.resume();

        check: {
            for (int i=0; i<100; i++) {
                try {
                    if (flippedRestore) {
                        assertEquals("hello", r1.echo("hello"));
                        assertEquals("10:123", r2.b(123));
                    } else {
                        assertEquals("10:123", r2.b(123));
                        assertEquals("hello", r1.echo("hello"));
                    }
                    break check;
                } catch (RemoteException e) {
                }
                Thread.sleep(100);
            }

            fail("Didn't reconnect");
        }

        r2 = mSession.root().az(20);
        r1 = r2.az();
        assertEquals("20:123", r2.b(123));
        assertEquals("world", r1.echo("world"));
    }

    @Test
    public void lenientWithInterfaceChange() throws Exception {
        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        R2 r2 = mSession.root().az(10);

        try {
            r2.b(123);
            fail();
        } catch (DisposedException e) {
            assertTrue(e.getMessage().contains("disconnect"));
        }

        // Define new remote interfaces, with some slight changes.
        changeRemoteInterfaces(true);

        mAcceptor.resume();

        check: {
            for (int i=0; i<100; i++) {
                try {
                    assertEquals("10->123", r2.b(123));
                    assertEquals(10, r2.c());
                    break check;
                } catch (RemoteException e) {
                }
                Thread.sleep(100);
            }

            fail("Didn't reconnect");
        }

        r2 = mSession.root().az(20);
        assertEquals("20->123", r2.b(123));
        assertEquals(20, r2.c());
    }

    @Test
    public void lenientWithInterfaceChangeBroken() throws Exception {
        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        R2 r2 = mSession.root().az(10);

        try {
            r2.b(123);
            fail();
        } catch (DisposedException e) {
            assertTrue(e.getMessage().contains("disconnect"));
        }

        // Define new remote interfaces, with some slight changes. Without an az method,
        // restore isn't possible.
        changeRemoteInterfaces(false);

        mAcceptor.resume();

        check: {
            for (int i=0; i<100; i++) {
                try {
                    r2.b(123);
                    fail();
                } catch (RemoteException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof UnimplementedException) {
                        assertTrue(cause.getMessage().contains(": az"));
                        break check;
                    }
                }
                Thread.sleep(100);
            }

            fail("Didn't detect method was unimplemented");
        }
    }

    private static class Loader extends ClassLoader {
        private final Map<String, Object> mLocal = new ConcurrentHashMap<>();

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Object local = mLocal.get(name);
            if (local == null) {
                return super.loadClass(name, resolve);
            } else if (local instanceof byte[]) {
                var bytes = (byte[]) local;
                var clazz = defineClass(name, bytes, 0, bytes.length);
                mLocal.put(name, clazz);
                return clazz;
            } else if (local instanceof Class) {
                return (Class) local;
            } else {
                throw new ClassNotFoundException(name);
            }
        }

        Class<?> finishLocal(String name, ClassMaker cm) throws ClassNotFoundException {
            mLocal.put(name, cm.finishBytes());
            return loadClass(name);
        }

        void drop(String name) {
            mLocal.put(name, name);
        }
    };

    public static class Acceptor implements Runnable, Closeable {
        final Environment mEnv;
        final ServerSocket mServerSocket;
        volatile boolean mClosed;
        boolean mDoSuspend;
        boolean mSuspended;
        Session mSession;

        public Acceptor(Environment env, ServerSocket ss) throws IOException {
            mEnv = env;
            mServerSocket = ss;
            ss.setSoTimeout(10);
        }

        @Override
        public void run() {
            try {
                while (!mClosed) {
                    synchronized (this) {
                        if (mDoSuspend) {
                            mSuspended = true;
                            notify();
                            do {
                                wait();
                            } while (mDoSuspend);
                            mSuspended = false;
                        }
                    }

                    Socket s;
                    try {
                        s = mServerSocket.accept();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }

                    try {
                        Session session = mEnv.accepted(s);

                        synchronized (this) {
                            mSession = session;
                        }
                    } catch (RemoteException e) {
                        CoreUtils.closeQuietly(s);
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (!mClosed) {
                    e.printStackTrace();
                }
            }
        }

        public void closeLastAccepted() {
            Session session;
            synchronized (this) {
                session = mSession;
                mSession = null;
            }
            if (session != null) {
                session.close();
            }
        }

        public synchronized void suspend() throws InterruptedException {
            mDoSuspend = true;
            while (!mSuspended) {
                wait();
            }
        }

        public synchronized void resume() {
            mDoSuspend = false;
            notify();
        }

        @Override
        public void close() {
            mClosed = true;
            CoreUtils.closeQuietly(mServerSocket);
            resume();
        }
    }

    public static interface R1 extends Remote {
        @Restorable
        R2 a(int param) throws RemoteException;

        @Restorable(lenient=true)
        R2 az(int param) throws RemoteException;

        R2 b(int param) throws RemoteException;

        Object echo(Object param) throws RemoteException;
    }

    public static interface R2 extends Remote {
        @Restorable
        R1 a() throws RemoteException;

        @Restorable(lenient=true)
        R1 az() throws RemoteException;

        String b(int param) throws RemoteException;

        int c() throws RemoteException;
    }

    private static class R1Server implements R1 {
        @Override
        public R2 a(int param) {
            return new R2Server(param);
        }

        @Override
        public R2 az(int param) {
            return a(param);
        }

        @Override
        public R2 b(int param) {
            return new R2Server(param);
        }

        @Override
        public Object echo(Object param) {
            return param;
        }
    }

    private static class R2Server implements R2 {
        private final int mParam;

        R2Server(int param) {
            mParam = param;
        }

        @Override
        public R1 a() {
            return new R1Server();
        }

        @Override
        public R1 az() {
            return a();
        }

        @Override
        public String b(int param) {
            return mParam + ":" + param;
        }

        @Override
        public int c() {
            return mParam;
        }
    }
}
