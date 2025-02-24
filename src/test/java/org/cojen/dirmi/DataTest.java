/*
 *  Copyright 2025 Cojen.org
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

import java.util.concurrent.atomic.AtomicLong;

import java.util.function.BiPredicate;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.MethodMaker;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public class DataTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(DataTest.class.getName());
    }

    private Environment mEnv;

    @Before
    public void setup() {
        mEnv = Environment.create();
    }

    @After
    public void tearDown() throws Exception {
        mEnv.close();
    }

    @Test
    public void basic() throws Exception {
        mEnv.reconnectDelayMillis(0);
        var server = new MainServer(false);
        assertFalse(server.nothing);
        mEnv.export("main", server);
        mEnv.connector(Connector.local(mEnv));
        var session = mEnv.connect(Main.class, "main", null);

        var main = session.root();

        assertEquals(1, main.id());
        assertEquals("bob", main.name());
        assertEquals(345, main.value());
        assertTrue(main.obj() instanceof Exception);
        assertTrue(main.obj2() instanceof Exception);
        assertTrue(server.nothing);

        var same = main.same();
        assertEquals(1, same.id());

        try {
            main.same2().id();
            fail();
        } catch (DataUnavailableException e) {
            // Data methods don't work with batches.
        }

        var next = main.next(false);
        assertEquals(3, next.id()); // is 3 because the batch call finished

        var listener = new BiPredicate<Session<?>, Throwable>() {
            private boolean connected;

            @Override
            public synchronized boolean test(Session<?> session, Throwable ex) {
                if (session.state() == Session.State.CONNECTED) {
                    connected = true;
                    notify();
                }
                return true;
            }

            /**
             * One-shot notification.
             */
            public synchronized void awaitConnected() throws InterruptedException {
                while (!connected) {
                    wait();
                }
                connected = false;
            }
        };

        session.addStateListener(listener);
        listener.awaitConnected();

        session.reconnect();
        listener.awaitConnected();

        assertEquals(4, main.id());
        assertEquals(4, same.id());
        assertEquals(4, main.same().id());

        restoreCheck: {
            AssertionError error = null;
            for (int i=1; i<=10; i++) {
                try {
                    assertTrue(next.id() > 4);
                    break restoreCheck;
                } catch (AssertionError e) {
                    error = e;
                }
                // The restore is performed in the background, so wait for it.
                Thread.sleep(i * 100);
            }
            throw error;
        }

        // If there's an exception thrown when calling the server-side data method, it's
        // treated as uncaught, and the pipe is closed. The client gets nothing but a
        // ClosedException.

        main.installHandler();

        try {
            main.next(true).id();
            fail();
        } catch (ClosedException e) {
        }

        main.uninstallHandler();

        assertEquals("broken", server.error.getMessage());
    }

    @Test
    public void mismatch() throws Exception {
        // Launch the server with a different set of data methods.

        Class<?> iface, server;
        {
            ClassMaker cm = ClassMaker.beginExplicit(Main.class.getName(), new Loader(), null)
                .public_().interface_().implement(Remote.class);
            cm.addMethod(int.class, "id").public_().abstract_()
                .addAnnotation(Data.class, true);
            cm.addMethod(String.class, "name").public_().abstract_()
                .addAnnotation(Data.class, true);
            cm.addMethod(String.class, "message").public_().abstract_()
                .addAnnotation(Data.class, true);
            cm.addMethod(Object.class, "obj").public_().abstract_().throws_(RemoteException.class);
            cm.addMethod(cm, "same").public_().abstract_().throws_(RemoteException.class);
            cm.addMethod(null, "installHandler").public_().abstract_()
                .addAnnotation(Data.class, true);
            iface = cm.finish();

            cm = ClassMaker.begin(null, iface.getClassLoader()).implement(iface).public_();
            cm.addConstructor().public_();
            cm.addMethod(int.class, "id").public_().return_(123);
            cm.addMethod(String.class, "name").public_().return_("myname");
            cm.addMethod(String.class, "message").public_().return_("message");
            cm.addMethod(Object.class, "obj").public_().return_("obj");
            MethodMaker mm = cm.addMethod(iface, "same").public_();
            mm.return_(mm.this_());
            cm.addMethod(null, "installHandler").public_();
            server = cm.finish();
        }

        mEnv.export("main", server.getConstructor().newInstance());

        mEnv.connector(Connector.local(mEnv));
        var session = mEnv.connect(Main.class, "main", null);
        var main = session.root();

        try {
            main.id();
            fail();
        } catch (DataUnavailableException e) {
            // Return type differs.
        }

        assertEquals("myname", main.name());

        try {
            main.value();
            fail();
        } catch (DataUnavailableException e) {
            // Isn't defined on the server.
        }

        try {
            main.nothing();
            fail();
        } catch (DataUnavailableException e) {
            // Isn't defined on the server.
        }

        try {
            main.obj();
            fail();
        } catch (DataUnavailableException e) {
            // Isn't a data method on the server.
        }

        assertEquals(main, main.same());

        try {
            main.installHandler();
            fail();
        }  catch (UnimplementedException e) {
            // The client expects a true remote call, but the server provides data.
        }
    }

    private static class Loader extends ClassLoader {
        Loader() {
            super(DataTest.class.getClassLoader());
        }
    }

    public static interface Main extends Remote {
        @Data
        long id();

        @Data
        String name();

        @Data
        int value();

        @Data
        void nothing();

        @Data @Serialized(filter="*")
        Object obj();

        @Data @Serialized(filter="java.base/*")
        Object obj2();

        Main same() throws RemoteException;

        @Batched
        Main same2() throws RemoteException;

        @Restorable
        Main next(boolean broken) throws RemoteException;

        void installHandler() throws RemoteException;

        void uninstallHandler() throws RemoteException;
    }

    static final class MainServer implements Main {
        static final AtomicLong id = new AtomicLong(0);

        final boolean broken;

        volatile boolean nothing;

        volatile Throwable error;

        MainServer(boolean broken) {
            this.broken = broken;
        }

        @Override
        public long id() {
            if (broken) {
                throw new Error("broken");
            }
            return id.addAndGet(1);
        }

        @Override
        public String name() {
            return "bob";
        }

        @Override
        public int value() {
            return 345;
        }

        @Override
        public void nothing() {
            nothing = true;
        }

        @Override
        public Object obj() {
            return new Exception("hello");
        }

        @Override
        public Object obj2() {
            return new Exception("world");
        }

        @Override
        public Main same() {
            return this;
        }

        @Override
        public Main same2() {
            return this;
        }

        @Override
        public Main next(boolean broken) {
            return new MainServer(broken);
        }

        @Override
        public void installHandler() {
            Session.current().uncaughtExceptionHandler((s, e) -> {
                error = e;
            });
        }

        @Override
        public void uninstallHandler() {
            Session.current().uncaughtExceptionHandler(null);
        }
    }
}
