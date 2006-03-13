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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestMultiplexer extends TestCase {
    private static final int STREAM_TEST_COUNT = 10000;
    private static final int STREAM_SHORT_TEST_COUNT = 100;

    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }

    public static TestSuite suite() {
        return new TestSuite(TestMultiplexer.class);
    }

    private Connection mServerCon;
    private Connection mClientCon;
    private String mThreadName;

    public TestMultiplexer(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        mThreadName = Thread.currentThread().getName();

        final Connecter connecter = new PipedConnecter();
        Thread t = new Thread() {
            public void run() {
                try {
                    mServerCon = connecter.accept();
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    fail();
                }
            }
        };
        t.start();
        mClientCon = connecter.connect();
        t.join();
    }

    protected void tearDown() throws Exception {
        mServerCon.close();
        mServerCon = null;
        mClientCon.close();
        mClientCon = null;

        Thread.currentThread().setName(mThreadName);
    }

    public void testBasic() throws Exception {
        final AcceptQueue acceptQueue = new AcceptQueue(mServerCon);
        acceptQueue.start();

        new Thread() {
            {
                setName("Remote");
            }

            public void run() {
                try {
                    Connection con = acceptQueue.getConnection();
                    int c = con.getInputStream().read();
                    assertEquals('Q', c);
                    con.getOutputStream().write('A');
                    con.close();
                } catch (IOException e) {
                    e.printStackTrace(System.out);
                    fail();
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    fail();
                }
            }
        }.start();

        Connecter connecter = new Multiplexer(mClientCon);
        Thread dummyAccepter = new DummyAccepter(connecter);
        dummyAccepter.start();

        final Connection con = connecter.connect();

        new Thread() {
            {
                setName("Writer");
            }

            public void run() {
                try {
                    OutputStream out = con.getOutputStream();
                    out.write('Q');
                    out.flush();
                } catch (IOException e) {
                    e.printStackTrace(System.out);
                    fail();
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    fail();
                }
            }
        }.start();

        int c = con.getInputStream().read();
        assertEquals('A', c);

        dummyAccepter.interrupt();
        acceptQueue.interrupt();
    }

    public void testStream() throws Exception {
        AcceptQueue acceptQueue = new AcceptQueue(mServerCon);
        acceptQueue.start();

        Connecter clientConnecter = new Multiplexer(mClientCon);
        Thread dummyAccepter = new DummyAccepter(clientConnecter);
        dummyAccepter.start();

        testStream(acceptQueue, clientConnecter, STREAM_TEST_COUNT);

        dummyAccepter.interrupt();
        acceptQueue.interrupt();
    }

    public void testMultipleStreams() throws Exception {
        final AcceptQueue acceptQueue = new AcceptQueue(mServerCon);
        acceptQueue.start();

        final Connecter clientConnecter = new Multiplexer(mClientCon);
        Thread dummyAccepter = new DummyAccepter(clientConnecter);
        dummyAccepter.start();

        int totalThreads = STREAM_TEST_COUNT / STREAM_SHORT_TEST_COUNT;
        
        // Limit the amount of active threads.
        final int permits = 10;
        final Semaphore sem = new Semaphore(permits);

        for (int i=0; i<totalThreads; i++) {
            sem.acquire();
            new Thread() {
                public void run() {
                    try {
                        testStream(acceptQueue, clientConnecter, STREAM_SHORT_TEST_COUNT);
                    } catch (Exception e) {
                        e.printStackTrace(System.out);
                        fail();
                    } finally {
                        sem.release();
                    }
                }
            }.start();
        }

        // Wait for all threads to finish.
        sem.acquire(permits);

        dummyAccepter.interrupt();
        acceptQueue.interrupt();
    }

    private void testStream(final AcceptQueue acceptQueue,
                            final Connecter clientConnecter,
                            final int testCount)
        throws Exception
    {
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

                Connection con = null;
                try {
                    con = acceptQueue.getConnection();

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
                    handleException(e);
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    fail();
                } catch (Throwable e) {
                    e.printStackTrace(System.out);
                    fail();
                }
            }
        };
        remote.start();

        final int rndSeed = 424342239;
        final Connection con = clientConnecter.connect();

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
                    for (int q=0; q<testCount; q++) {
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
                    e.printStackTrace(System.out);
                    fail();
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    fail();
                }
            }
        };

        reader.start();

        String originalName = Thread.currentThread().getName();
        Thread.currentThread().setName("Local Writer");

        // This thread writes the random data. Its random number generater is
        // sync'd with the reader.

        Random rnd = new Random(rndSeed);

        byte[] sentBytes = new byte[10000];

        for (int q=0; q<testCount; q++) {
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
        con.close();
    }

    void handleException(IOException e) {
        String message = e.getMessage();
        if (message != null && message.toLowerCase().contains("close")) {
            //
        } else {
            e.printStackTrace(System.out);
            fail();
        }
    }

    private class DummyAccepter extends Thread {
        private final Connecter mConnecter;

        DummyAccepter(Connecter connecter) {
            mConnecter = connecter;
            setName("Dummy Accepter");
        }

        public void run() {
            try {
                while (true) {
                    Connection con = mConnecter.accept();
                    if (con != null) {
                        fail();
                        con.close();
                    }
                }
            } catch (InterruptedIOException e) {
                //
            } catch (IOException e) {
                handleException(e);
            } catch (Exception e) {
                e.printStackTrace(System.out);
                fail();
            }
        }
    }

    private class AcceptQueue extends Thread {
        private final Connection mMasterCon;
        private final BlockingQueue<Connection> mQueue;

        private Connecter mConnecter;

        AcceptQueue(Connection masterCon) {
            mMasterCon = masterCon;
            mQueue = new LinkedBlockingQueue<Connection>();
            setName("Accept Queue");
        }

        public Connection getConnection() throws InterruptedException {
            return mQueue.take();
        }

        public void run() {
            try {
                mConnecter = new Multiplexer(mMasterCon);
                while (true) {
                    mQueue.put(mConnecter.accept());
                }
            } catch (InterruptedIOException e) {
                //
            } catch (IOException e) {
                handleException(e);
            } catch (Exception e) {
                e.printStackTrace(System.out);
                fail();
            }
        }
    }
}
