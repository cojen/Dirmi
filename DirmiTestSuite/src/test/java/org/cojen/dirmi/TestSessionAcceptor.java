package org.cojen.dirmi;

import org.junit.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Tests for the SessionAcceptor class.
 *
 * Created by kstemen on 11/21/16.
 */
public class TestSessionAcceptor {
    private static Environment env;

    private SessionListener listener;
    private SessionAcceptor acceptor;
    private RemoteFaceServer remoteServer;

    @BeforeClass
    public static void createEnv() {
        env = new Environment(1000, null, new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                synchronized (System.out) {
                    System.out.println("Exception in thread \"" + t.getName() + "\" " + e);
                    if (e instanceof java.io.InvalidClassException) {
                        // Reduce output when testing broken serializable objects.
                        return;
                    }
                    e.printStackTrace(System.out);
                }
            }
        });
    }

    @AfterClass
    public static void closeEnv() throws Exception {
        if (env != null) {
            env.close();
            env = null;
        }
    }

    @Before
    public void setUp() throws Exception {
        remoteServer = new RemoteFaceServer();

        acceptor = env.newSessionAcceptor(new InetSocketAddress("127.0.0.1", 0));

        listener = new SessionListener() {
            @Override
            public void established(Session session) throws IOException {
                acceptor.accept(this);
                try {
                    session.send(remoteServer);
                } catch (IOException e) {
                    try {
                        session.close();
                    } catch (IOException e2) {
                        // Ignore.
                    }
                }
            }

            @Override
            public void establishFailed(IOException cause) throws IOException {
                acceptor.accept(this);
            }

            @Override
            public void acceptFailed(IOException cause) {
            }
        };
        acceptor.accept(listener);
    }

    @After
    public void tearDown() throws Exception {
        if (acceptor != null) {
            acceptor.close();
            acceptor = null;
        }
    }

    private void ping(long connectTimeoutMillis) throws IOException {
        Session localSession = env.newSessionConnector
                ((InetSocketAddress) acceptor.getLocalAddress()).connect(
                        connectTimeoutMillis, TimeUnit.MILLISECONDS);
        RemoteFace localServer = (RemoteFace) localSession.receive();
        String reply = localServer.echoObject("test");
        assertEquals("test", reply);
        localSession.close();
    }

    private void connectAndClose(long connectTimeoutMillis) throws IOException {
        Session localSession = env.newSessionConnector
                ((InetSocketAddress) acceptor.getLocalAddress()).connect(
                connectTimeoutMillis, TimeUnit.MILLISECONDS);
        RemoteFace localServer = (RemoteFace) localSession.receive();
        localSession.close();
    }

    @Test
    /**
     * Verify when a client times out while trying to connect to the acceptor, that doesn't
     * break the acceptor. Specifically the acceptor must be able to accept new connections
     * afterwards.
     */
    public void connectTimeout() throws IOException {
        // A large connect timeout. The timeout is high enough that the call should easily
        // finish without timing out. For this test to cover the right paths, this timeout
        // (when converted to seconds) must be lower than
        // StandardSession.CREATE_TIMEOUT_SECONDS.
        final int SUCCESS_TIMEOUT_MS = 14_000;
        // Call ping() once without timing it, to warm up the caches
        ping(SUCCESS_TIMEOUT_MS);

        // Now get an estimate of how long each call takes
        long start = System.currentTimeMillis();
        for(int i = 0; i < 3; i++) {
            connectAndClose(SUCCESS_TIMEOUT_MS);
        }
        long end = System.currentTimeMillis();
        long avgTime = (end - start) / 3;

        // Call ping again, but use a connect timeout. p is a percentage of avgTime.
        int timeouts = 0;
        for (int p = 0; p < 100; p += 10) {
            try {
                ping(avgTime * p / 100);
            } catch (RemoteTimeoutException e) {
                timeouts++;
                // This test purposely uses a timeout that is too low, so this exception is
                // expected. Now that the client has timed out, verify the server is still
                // healthy.
                ping(SUCCESS_TIMEOUT_MS);
            }
        }
        assertNotSame("None of the calls timed out", 0, timeouts);
    }
}
