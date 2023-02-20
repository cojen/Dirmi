/*
 *  Copyright 2023 Cojen.org
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

import java.net.ServerSocket;

import java.util.ArrayList;

import java.util.concurrent.atomic.AtomicReference;

import java.util.function.BiPredicate;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StateListenerTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(StateListenerTest.class.getName());
    }

    private Environment mEnv;
    private ServerSocket mServerSocket;
    private RestorableTest.Acceptor mAcceptor;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mEnv = Environment.create();
        mEnv.export("main", new R1Server());
        mServerSocket = new ServerSocket(0);
        mAcceptor = new RestorableTest.Acceptor(mEnv, mServerSocket);
        mEnv.execute(mAcceptor);

        mSession = mEnv.connect(R1.class, "main", "localhost", mServerSocket.getLocalPort());
    }

    public static interface R1 extends Remote {
        Object echo(Object param) throws RemoteException;
    }

    private static class R1Server implements R1 {
        @Override
        public Object echo(Object param) {
            return param;
        }
    }

    @After
    public void teardown() throws Exception {
        if (mAcceptor != null) {
            mAcceptor.close();
        }
        mEnv.close();
    }

    @Test
    public void immediateNotify() throws Exception {
        Listener[] listeners = {
            new Listener(0),
            new Listener(3),
            new Listener(0),
            new Listener(0),
            new Listener(1),
        };

        for (var L : listeners) {
            mSession.addStateListener(L);
            L.assertState(Session.State.CONNECTED);
        }

        var caught = new AtomicReference<Throwable>();

        mSession.uncaughtExceptionHandler((session, ex) -> caught.set(ex));

        mSession.addStateListener((session, ex) -> {
            throw new RuntimeException("hello");
        });

        assertEquals("hello", caught.get().getMessage());
        caught.set(null);

        mSession.close();

        assertEquals("hello", caught.get().getMessage());

        listeners[0].assertEmpty();
        listeners[1].assertState(Session.State.CLOSED);
        listeners[2].assertEmpty();
        listeners[3].assertEmpty();
        listeners[4].assertEmpty();
    }

    @Test
    public void addTwice() throws Exception {
        var listener = new Listener(2);

        mSession.addStateListener(listener);
        listener.assertState(Session.State.CONNECTED);

        mSession.addStateListener(listener);
        listener.assertState(Session.State.CONNECTED);

        mSession.close();

        listener.assertEmpty();
    }

    @Test
    public void removeOneUponClose() throws Exception {
        var listener = new Listener(2);

        mSession.addStateListener(listener);
        listener.assertState(Session.State.CONNECTED);

        mSession.close();

        listener.assertState(Session.State.CLOSED);
    }

    @Test
    public void removeManyUponClose() throws Exception {
        var L1 = new Listener(2);
        var L2 = new Listener(2);

        mSession.addStateListener(L1);
        L1.assertState(Session.State.CONNECTED);

        mSession.addStateListener(L2);
        L2.assertState(Session.State.CONNECTED);

        mSession.close();

        L1.assertState(Session.State.CLOSED);
        L2.assertState(Session.State.CLOSED);
    }

    @Test
    public void failRemoveOneUponClose() throws Exception {
        var listener = new Listener(2) {
            @Override
            public boolean test(Session<?> session, Throwable ex) {
                if (mKeep == 1) {
                    throw new RuntimeException("hello");
                }
                return super.test(session, ex);
            }
        };

        mSession.addStateListener(listener);
        listener.assertState(Session.State.CONNECTED);

        var caught = new AtomicReference<Throwable>();

        mSession.uncaughtExceptionHandler((session, ex) -> caught.set(ex));

        mSession.close();

        assertEquals("hello", caught.get().getMessage());

        listener.assertEmpty();
    }

    static class Listener implements BiPredicate<Session<?>, Throwable> {
        private final ArrayList<Session.State> mStates = new ArrayList<>();

        protected int mKeep;

        Listener(int keep) {
            mKeep = keep;
        }

        @Override
        public boolean test(Session<?> session, Throwable ex) {
            mStates.add(session.state());
            return --mKeep > 0;
        }

        void assertState(Session.State state) {
            assertTrue(mStates.size() == 1);
            assertEquals(state, mStates.get(0));
            mStates.clear();
        }

        void assertEmpty() {
            assertTrue(mStates.isEmpty());
        }
    };
}
