/*
 *  Copyright 2008 Brian S O'Neill
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

import java.io.InterruptedIOException;
import java.io.IOException;

import java.util.Random;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;
import junit.framework.TestSuite;
import static org.junit.Assert.*;

import dirmi.core.ThreadPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestChannelStreams extends TestCase {
    private static final int STREAM_TEST_COUNT = 10000;

    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }

    public static TestSuite suite() {
        return new TestSuite(TestChannelStreams.class);
    }

    ChannelInputStream mIn;
    ChannelOutputStream mOut;

    public TestChannelStreams(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        PipedInputStream pin = new PipedInputStream();
        PipedOutputStream pout = new PipedOutputStream(pin);
        mIn = new ChannelInputStream(pin);
        mOut = new ChannelOutputStream(pout);
    }

    protected void tearDown() throws Exception {
        mIn.close();
        mOut.close();
    }

    public void testStreamNoReset() throws Exception {
        final long seed = 341487102938L;
        final long seed2 = 21851328712L;

        Thread reader = new Thread() {
            {
                setName("Reader");
            }

            public void run() {
                Random rnd = new Random(seed);
                Random rnd2 = new Random(seed2);
                byte[] bytes = new byte[10000];

                try {
                    while (true) {
                        if (rnd2.nextInt(100) < 1) {
                            // Read one byte and verify.
                            int c = mIn.read();
                            int expected = rnd.nextInt() & 0xff;
                            assertTrue(expected == c);
                        } else {
                            // Read and verify a chunk.
                            int size = rnd2.nextInt(10000);
                            int offset, length;
                            if (size == 0) {
                                offset = 0;
                                length = 0;
                            } else {
                                offset = rnd2.nextInt(Math.min(100, size));
                                length =
                                    size - offset - rnd2.nextInt(Math.min(100, size - offset));
                                offset = 0;
                                length = size;
                            }
                            int amt = mIn.read(bytes, offset, length);
                            for (int i=0; i<amt; i++) {
                                int c = bytes[offset + i] & 0xff;
                                int expected = rnd.nextInt() & 0xff;
                                if (c != expected) {
                                    System.out.println(c);
                                    System.out.println(expected);
                                    System.out.println(i);
                                    System.out.println(size);
                                    System.out.println(amt);
                                }
                                assertTrue(c == expected);
                            }
                        }
                    }
                } catch (InterruptedIOException e) {
                    //
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                } finally {
                    try {
                        mIn.close();
                    } catch (Exception e) {
                    }
                }
            }
        };

        reader.start();

        // Write random data, to be verified by reader.

        Random rnd = new Random(seed);
        Random rnd2 = new Random(seed);
        byte[] sentBytes = new byte[10000];

        for (int q=0; q<STREAM_TEST_COUNT; q++) {
            if (rnd2.nextInt(100) < 1) {
                // Write one byte.
                int b = (byte) rnd.nextInt() & 0xff;
                mOut.write(b);
                if (rnd2.nextInt(10) < 1) {
                    mOut.flush();
                }
            } else {
                // Write a chunk.
                int size = rnd2.nextInt(10000);
                int offset, length;
                if (size == 0) {
                    offset = 0;
                    length = 0;
                } else {
                    offset = rnd2.nextInt(Math.min(100, size));
                    length = size - offset -
                        rnd2.nextInt(Math.min(100, size - offset));
                }
                for (int i=0; i<length; i++) {
                    sentBytes[offset + i] = (byte) rnd.nextInt();
                }
                mOut.write(sentBytes, offset, length);
                if (rnd2.nextInt(10) < 9) {
                    mOut.flush();
                }
            }
        }

        mOut.flush();

        reader.interrupt();
        reader.join();
    }

    public void testReset() throws Exception {
        ExecutorService pool = new ThreadPool(10, true);

        // Run several times to account for any odd race conditions.
        for (int i=0; i<100; i++) {
            Future<Object> a = pool.submit(new Callable<Object>() {
                public Object call() throws Exception {
                    mOut.write('A');
                    mOut.flush();
                    mOut.write('B');
                    // Reset also discards 'B'.
                    mOut.reset();
                    mOut.write('C');
                    mOut.flush();
                    return null;
                }
            });

            Future<int[]> b = pool.submit(new Callable<int[]>() {
                public int[] call() throws Exception {
                    int a = mIn.read();
                    // Should see EOF because stream is in a reset state.
                    int b = mIn.read();
                    mIn.reset();
                    int c = mIn.read();
                    return new int[] {a, b, c};
                }
            });

            a.get();
            int[] results = b.get();
            assertArrayEquals(new int[] {'A', -1, 'C'}, results);
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    public void testClose() throws Exception {
        ExecutorService pool = new ThreadPool(10, true);

        Future<int[]> r = pool.submit(new Callable<int[]>() {
            public int[] call() throws Exception {
                int a = mIn.read();
                int b;
                try {
                    b = mIn.read();
                } catch (IOException e) {
                    // Expected.
                    b = 1000;
                }
                int c;
                try {
                    mIn.reset();
                    c = 0;
                } catch (IOException e) {
                    // Expected.
                    c = 2000;
                }
                return new int[] {a, b, c};
            }
        });

        mOut.write('Z');
        mOut.close();
        try {
            mOut.write('?');
            fail();
        } catch (IOException e) {
        }
        try {
            mOut.reset();
            fail();
        } catch (IOException e) {
        }

        int[] results = r.get();
        assertArrayEquals(new int[] {'Z', 1000, 2000}, results);

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }
}
