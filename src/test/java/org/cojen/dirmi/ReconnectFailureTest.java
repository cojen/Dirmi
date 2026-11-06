/*
 *  Copyright 2026 Cojen.org
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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
 
import java.net.ServerSocket;
import java.net.Socket;
 
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
 
import org.junit.*;
import static org.junit.Assert.*;

 
/**
 * Tests that a session keeps trying to reconnect after a transient I/O failure.
 *
 * <p>A reconnect has two phases: establishing a new connection, and then using it to
 * restore session state (info requests, etc). A failure in the first phase is retried. A
 * failure in the second phase used to leave the session permanently disconnected, with no
 * further reconnect attempts.
 *
 * @author Claude
 */
public class ReconnectFailureTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ReconnectFailureTest.class.getName());
    }
 
    public static interface Hello extends Remote {
        String hi() throws RemoteException;
    }
 
    private static class HelloServer implements Hello {
        @Override
        public String hi() {
            return "hi";
        }
    }
 
    /** Connector failure modes, applied to the first connection attempted after arming. */
    private enum Fault {
        /** The connector throws before any connection is established. */
        CONNECT,
        /** The connection is established, but writes fail after the initial handshake. */
        AFTER_HANDSHAKE
    }
 
    private Environment mServerEnv;
    private ServerSocket mServerSocket;
    private Environment mClientEnv;
 
    private final AtomicInteger mAttempts = new AtomicInteger();
    private final AtomicBoolean mArmed = new AtomicBoolean();
    private volatile Fault mFault;
    private volatile Socket mFirstSocket;
 
    @Before
    public void setup() throws Exception {
        mServerEnv = Environment.create();
        mServerEnv.export("main", new HelloServer());
 
        mServerSocket = new ServerSocket(0);
        var thread = new Thread(() -> {
            try {
                while (true) {
                    mServerEnv.accepted(mServerSocket.accept());
                }
            } catch (IOException e) {
                // Server socket closed.
            }
        });
        thread.setDaemon(true);
        thread.start();
 
        mClientEnv = Environment.create();
        mClientEnv.reconnectDelayMillis(200);
        mClientEnv.pingTimeoutMillis(5000);
        mClientEnv.connector(this::connect);
    }
 
    @After
    public void teardown() throws Exception {
        if (mClientEnv != null) {
            mClientEnv.close();
        }
        if (mServerEnv != null) {
            mServerEnv.close();
        }
        if (mServerSocket != null) {
            mServerSocket.close();
        }
    }
 
    private void connect(Session<?> session) throws IOException {
        mAttempts.incrementAndGet();
 
        // Only the first connection attempted after arming is faulty. The server is healthy,
        // and so any later attempt can succeed.
        Fault fault = mArmed.compareAndSet(true, false) ? mFault : null;
 
        if (fault == Fault.CONNECT) {
            throw new IOException("injected connect failure");
        }
 
        var socket = new Socket();
        socket.connect(session.remoteAddress());
 
        if (mFirstSocket == null) {
            mFirstSocket = socket;
        }
 
        OutputStream out = socket.getOutputStream();
 
        if (fault == Fault.AFTER_HANDSHAKE) {
            // The reconnect handshake is written as a single flush. Allow that one write, and
            // then fail all later ones, which are sent after the new connection has already
            // been established.
            var writes = new AtomicInteger();
            out = new FilterOutputStream(out) {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }
 
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (writes.incrementAndGet() > 1) {
                        throw new IOException("injected failure after handshake");
                    }
                    this.out.write(b, off, len);
                }
            };
        }
 
        session.connected(socket.getLocalSocketAddress(), socket.getRemoteSocketAddress(),
                          socket.getInputStream(), out);
    }
 
    @Test(timeout = 30_000)
    public void connectFailureIsRetried() throws Exception {
        reconnectAfterFault(Fault.CONNECT);
    }
 
    @Test(timeout = 30_000)
    public void failureAfterHandshakeIsRetried() throws Exception {
        reconnectAfterFault(Fault.AFTER_HANDSHAKE);
    }
 
    private void reconnectAfterFault(Fault fault) throws Exception {
        Session<Hello> session = mClientEnv.connect
            (Hello.class, "main", mServerSocket.getLocalSocketAddress());
 
        assertEquals("hi", session.root().hi());
        assertEquals(Session.State.CONNECTED, session.state());
 
        // Break the session, and make the first reconnect attempt fail.
        mFault = fault;
        mArmed.set(true);
        int attemptsBefore = mAttempts.get();
        mFirstSocket.close();
 
        // The session should recover on its own, because the server is healthy.
        long end = System.nanoTime() + 15_000_000_000L;
        String result = null;
 
        while (System.nanoTime() < end) {
            Thread.sleep(100);
            try {
                result = session.root().hi();
                if ("hi".equals(result)) {
                    break;
                }
            } catch (RemoteException e) {
                // Not reconnected yet.
            }
        }
 
        assertEquals("Session never reconnected; state=" + session.state() +
                     ", attempts since failure=" + (mAttempts.get() - attemptsBefore),
                     "hi", result);
 
        assertEquals(Session.State.CONNECTED, session.state());
 
        // At least the failed attempt, and then a successful one.
        assertTrue(mAttempts.get() - attemptsBefore >= 2);
    }
}
