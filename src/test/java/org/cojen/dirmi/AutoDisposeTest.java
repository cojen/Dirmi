/*
 *  Copyright 2024 Cojen.org
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

import java.io.IOException;

import java.net.ServerSocket;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public class AutoDisposeTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(AutoDisposeTest.class.getName());
    }

    private Environment mServerEnv, mClientEnv;
    private R1Server mServer;
    private ServerSocket mServerSocket;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mServerEnv = Environment.create();
        mServer = new R1Server();
        mServerEnv.export("main", mServer);
        mServerSocket = new ServerSocket(0);
        mServerEnv.acceptAll(mServerSocket);

        mClientEnv = Environment.create();
        mSession = mClientEnv.connect(R1.class, "main", "localhost", mServerSocket.getLocalPort());
    }

    @After
    public void teardown() throws Exception {
        if (mServerEnv != null) {
            mServerEnv.close();
        }
        if (mClientEnv != null) {
            mClientEnv.close();
        }
    }

    @Test
    public void basic() throws Exception {
        R1 root = mSession.root();

        assertSame(root, root.self());

        int sleep = 100;

        while (true) {
            // Need a different instance because the root cannot auto-dispose.
            R1 alt = root.alt();

            assertSame(alt, root.alt());
            assertEquals("hello", alt.echo("hello"));

            long id = extractId(alt);
            alt = null;

            System.gc();

            // Wait for ping task to flush the dispose message.
            Thread.sleep(sleep);

            long newId = extractId(root.alt());

            if (newId != id) {
                // Original remote object was disposed and a new one was created.
                break;
            }

            // Message not received yet by the server, so try again.
            sleep <<= 1;
            
            if (sleep >= 60_000) {
                fail("not automatically disposed in a timely fashion");
            }
        }
    }

    @Test
    public void noReply() throws Exception {
        R2Server.mDetached = 0;

        R1 root = mSession.root();
        R2 r2 = root.r2();

        r2.update("hello");
        r2.update("world");

        check: {
            for (int i=0; i<10; i++) {
                if ("world".equals(r2.getMessage())) {
                    break check;
                }
            }
            fail();
        }

        long id = extractId(r2);

        r2 = null;

        int sleep = 100;

        assertEquals(0, R2Server.mDetached);

        while (true) {
            System.gc();

            // Wait for background messages to be sent.
            Thread.sleep(sleep);

            long newId = extractId(root.r2());

            if (newId != id) {
                // Original remote object was disposed and a new one was created.
                assertEquals(1, R2Server.mDetached);
                break;
            }

            // Backround messages not sent yet, so try again.
            sleep <<= 1;
            
            if (sleep >= 60_000) {
                fail("not automatically disposed in a timely fashion");
            }
        }
    }

    @Test
    public void race() throws Exception {
        // Test that an auto disposed object isn't disposed before all the server side
        // instances have been received.

        R2Server.mDetached = 0;

        R1 root = mSession.root();

        R2 first = root.r2();

        Pipe p = root.receive(null);
        p.flush();
        R2 second = (R2) p.readObject();
        second.updateAndWaitForAck("hello");

        // Allow the r2 objects to be locally collected, but the remote skeleton for the second
        // cannot be disposed yet.
        first = null;
        second = null;

        // Wait for the first r2 object to be disposed and detached.
        waitForDetached(1);

        assertTrue(root.doFlush());

        // Force a few collections, which should have no effect.
        for (int i=1; i<=2; i++) {
            System.gc();
            Thread.sleep(1000);
        }

        assertEquals(1, R2Server.mDetached);

        second = (R2) p.readObject();
        p.close();
        p = null;

        assertEquals("hello", second.getMessage());
        second = null;

        // Wait for the second r2 object to be disposed and detached.
        waitForDetached(2);
    }

    private static void waitForDetached(int detached) throws Exception {
        for (int i=0; i<100; i++) {
            System.gc();
            int actual = R2Server.mDetached;
            if (actual == detached) {
                return;
            }
            if (actual > detached) {
                break;
            }
            Thread.sleep(100);
        }

        assertEquals(detached, R2Server.mDetached);
    }

    private static long extractId(Object obj) {
        String str = obj.toString();
        int ix = str.indexOf("id=");
        if (ix < 0) {
            fail();
        }
        ix += 3;
        int ix2 = str.indexOf(",", ix);
        if (ix2 < 0) {
            fail();
        }
        return Long.parseLong(str.substring(ix, ix2));
    }

    @AutoDispose
    public static interface R1 extends Remote {
        R1 self() throws RemoteException;

        R1 alt() throws RemoteException;

        String echo(String msg) throws RemoteException;

        @Restorable
        R2 r2() throws RemoteException;

        Pipe receive(Pipe pipe) throws IOException;

        boolean doFlush() throws IOException;
    }

    @AutoDispose
    public static interface R2 extends Remote {
        @NoReply
        void update(String msg) throws RemoteException;

        void updateAndWaitForAck(String msg) throws RemoteException;

        String getMessage() throws RemoteException;
    }

    private static class R1Server implements R1 {
        private R1Server mAlt;
        private R2Server mR2;

        @Override
        public R1 self() {
            return this;
        }

        @Override
        public synchronized R1 alt() {
            if (mAlt == null) {
                mAlt = new R1Server();
            }
            return mAlt;
        }

        @Override
        public String echo(String msg) {
            return msg;
        }

        @Override
        public R2 r2() {
            if (mR2 == null) {
                mR2 = new R2Server();
            }
            return mR2;
        }

        @Override
        public synchronized Pipe receive(Pipe pipe) throws IOException {
            mPipe = pipe;
            mR2 = new R2Server();
            pipe.writeObject(mR2);
            pipe.flush();
            pipe.writeObject(mR2);
            return null;
        }

        private Pipe mPipe;

        @Override
        public synchronized boolean doFlush() throws IOException {
            if (mPipe == null) {
                return false;
            } else {
                mPipe.flush();
                mPipe.close();
                mPipe = null;
                return true;
            }
        }
    }

    private static class R2Server implements R2, SessionAware {
        static volatile int mDetached;

        private volatile String mMessage;

        @Override
        public void update(String msg) {
            mMessage = msg;
        }

        @Override
        public void updateAndWaitForAck(String msg) {
            mMessage = msg;
        }

        @Override
        public String getMessage() {
            return mMessage;
        }

        @Override
        public void attached(Session<?> s) {
        }

        @Override
        public void detached(Session<?> s) {
            mDetached++;
        }
    }
}
