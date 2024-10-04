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

        while (true) {
            System.gc();

            // Wait for background messages to be sent.
            Thread.sleep(sleep);

            long newId = extractId(root.r2());

            if (newId != id) {
                // Original remote object was disposed and a new one was created.
                break;
            }

            // Backround messages not sent yet, so try again.
            sleep <<= 1;
            
            if (sleep >= 60_000) {
                fail("not automatically disposed in a timely fashion");
            }
        }
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

        R2 r2() throws RemoteException;
    }

    @AutoDispose
    public static interface R2 extends Remote {
        @NoReply
        void update(String msg) throws RemoteException;

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
    }

    private static class R2Server implements R2 {
        private volatile String mMessage;

        @Override
        public void update(String msg) {
            mMessage = msg;
        }

        @Override
        public String getMessage() {
            return mMessage;
        }
    }
}
