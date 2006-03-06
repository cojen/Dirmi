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

package dirmi.io;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Random;

import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestMultiplexer extends TestCase {
    private static final int STREAM_TEST_COUNT = 100000;

    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }

    public static TestSuite suite() {
        return new TestSuite(TestMultiplexer.class);
    }

    private Connection mServerCon;
    private Connection mClientCon;
    private String mThreadName;
    volatile boolean mTestRunning;

    public TestMultiplexer(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        mThreadName = Thread.currentThread().getName();

        final Connector connector = new PipedConnector();
        Thread t = new Thread() {
            public void run() {
                try {
                    mServerCon = connector.accept();
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                }
            }
        };
        t.start();
        mClientCon = connector.connect();
        t.join();

        mTestRunning = true;
    }

    protected void tearDown() throws Exception {
        mServerCon.close();
        mServerCon = null;
        mClientCon.close();
        mClientCon = null;

        Thread.currentThread().setName(mThreadName);
        mTestRunning = false;
    }

    public void testBasic() throws Exception {
        Thread remote = new Thread() {
            public void run() {
                try {
                    Connector connector = new Multiplexer(mServerCon);
                    Connection con = connector.accept();
                    int c = con.getInputStream().read();
                    assertEquals('Q', c);
                    con.getOutputStream().write('A');
                    con.close();
                    connector.accept();
                } catch (IOException e) {
                    if (mTestRunning) {
                        e.printStackTrace();
                        fail();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                }
            }
        };
        remote.start();

        Connector connector = new Multiplexer(mClientCon);
        Thread acceptor = new DummyAcceptor(connector);
        acceptor.start();

        Connection con = connector.connect();
        OutputStream out = con.getOutputStream();
        out.write('Q');
        out.flush();
        int c = con.getInputStream().read();
        assertEquals('A', c);

        mTestRunning = false;

        remote.interrupt();
        acceptor.interrupt();
    }

    public void testStream() throws Exception {
        // Exercises a single multiplexed connection by sending random data,
        // echoing it back logicaly complimented, and checking if data is
        // correct. In addition, the chunk sizes of the data read or written is
        // randomized.

        // Remote thread reads and echos back.
        Thread remote = new Thread() {
            {
                setName("Remote");
            }

            public void run() {
                Random rnd = new Random(674582214);
                byte[] bytes = new byte[10000];

                Thread acceptor = null;
                try {
                    Connector connector = new Multiplexer(mServerCon);
                    Connection con = connector.accept();
                    acceptor = new DummyAcceptor(connector);
                    acceptor.start();

                    // Everything read is echoed back, complimented.
                    while (true) {
                        if (rnd.nextInt(100) < 1) {
                            // Read and write one byte.
                            int c = con.getInputStream().read();
                            con.getOutputStream().write(~c);
                        } else {
                            // Read and write a chunk.
                            int size = rnd.nextInt(10000);
                            int offset, length;
                            if (size == 0) {
                                offset = 0;
                                length = 0;
                            } else {
                                offset = rnd.nextInt(Math.min(100, size));
                                length = size - offset - rnd.nextInt(Math.min(100, size - offset));
                            }
                            int amt = con.getInputStream().read(bytes, offset, length);
                            for (int i=0; i<amt; i++) {
                                bytes[offset + i] = (byte) (~bytes[offset + i]);
                            }
                            con.getOutputStream().write(bytes, offset, amt);
                        }
                        con.getOutputStream().flush();
                    }
                } catch (IOException e) {
                    if (mTestRunning) {
                        e.printStackTrace();
                        fail();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                } finally {
                    if (acceptor != null) {
                        acceptor.interrupt();
                    }
                }
            }
        };
        remote.start();

        final int rndSeed = 424342239;
        Thread.currentThread().setName("Local");

        Connector connector = new Multiplexer(mClientCon);
        Thread acceptor = new DummyAcceptor(connector);
        acceptor.start();

        final Connection con = connector.connect();

        // Write random numbers, and read them back, ensuring the result is correct.

        // Reader thread ensures echoed data is correct.
        Thread reader = new Thread() {
            {
                setName("Local Reader");
            }

            public void run() {
                Random rnd = new Random(rndSeed);
                byte[] sentBytes = new byte[10000];
                byte[] readBytes = new byte[10000];

                try {
                    for (int q=0; q<STREAM_TEST_COUNT; q++) {
                        if (rnd.nextInt(100) < 1) {
                            // Read one byte.
                            int b = (byte) rnd.nextInt();
                            int c = con.getInputStream().read();
                            assertTrue(~b == c);
                        } else {
                            // Read a chunk.
                            int size = rnd.nextInt(10000);
                            int offset, length;
                            if (size == 0) {
                                offset = 0;
                                length = 0;
                            } else {
                                offset = rnd.nextInt(Math.min(100, size));
                                length = size - offset -
                                    rnd.nextInt(Math.min(100, size - offset));
                            }
                            for (int i=0; i<length; i++) {
                                sentBytes[offset + i] = (byte) rnd.nextInt();
                            }
                            while (true) {
                                int amt = con.getInputStream().read(readBytes, offset, length);
                                if (length > 0) {
                                    assertTrue(amt > 0);
                                }
                                assertTrue(amt <= length);
                                if (amt >= length) {
                                    break;
                                }
                                for (int i=0; i<amt; i++) {
                                    assertTrue(~sentBytes[offset + i] == readBytes[offset + i]);
                                }
                                offset += amt;
                                length -= amt;
                            }
                        }
                    }
                } catch (IOException e) {
                    if (mTestRunning) {
                        e.printStackTrace();
                        fail();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                }

            }
        };

        reader.start();

        // This thread writes the random data. Its random number generator is
        // sync'd with the reader.

        Random rnd = new Random(rndSeed);

        byte[] sentBytes = new byte[10000];

        for (int q=0; q<STREAM_TEST_COUNT; q++) {
            if (rnd.nextInt(100) < 1) {
                // Write one byte.
                int b = (byte) rnd.nextInt();
                con.getOutputStream().write(b);
                con.getOutputStream().flush();
            } else {
                // Write a chunk.
                int size = rnd.nextInt(10000);
                int offset, length;
                if (size == 0) {
                    offset = 0;
                    length = 0;
                } else {
                    offset = rnd.nextInt(Math.min(100, size));
                    length = size - offset -
                        rnd.nextInt(Math.min(100, size - offset));
                }
                for (int i=0; i<length; i++) {
                    sentBytes[offset + i] = (byte) rnd.nextInt();
                }
                con.getOutputStream().write(sentBytes, offset, length);
                con.getOutputStream().flush();
            }
        }

        reader.join();

        mTestRunning = false;

        acceptor.interrupt();
        remote.interrupt();
    }

    private class DummyAcceptor extends Thread {
        private final Connector mConnector;

        DummyAcceptor(Connector connector) {
            mConnector = connector;
            setName(Thread.currentThread().getName() + " Acceptor");
        }

        public void run() {
            try {
                while (true) {
                    Connection con = mConnector.accept();
                    if (con != null) {
                        fail();
                        con.close();
                    }
                }
            } catch (IOException e) {
                if (mTestRunning) {
                    e.printStackTrace();
                    fail();
                }
            } catch (Exception e) {
                e.printStackTrace();
                fail();
            }
        }
    }
}
