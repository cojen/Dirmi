/*
 *  Copyright 2006-2009 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.InterruptedIOException;

import java.util.Random;

import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestPipedStreams extends TestCase {
    private static final int STREAM_TEST_COUNT = 100000;

    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }

    public static TestSuite suite() {
        return new TestSuite(TestPipedStreams.class);
    }

    PipedInputStream mIn;
    PipedOutputStream mOut;

    public TestPipedStreams(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        mIn = new PipedInputStream();
        mOut = new PipedOutputStream(mIn);
    }

    protected void tearDown() throws Exception {
        mIn.close();
        mOut.close();
    }

    public void testStream() throws Exception {
        final long seed = 342487102938L;

        Thread reader = new Thread() {
            {
                setName("Reader");
            }

            public void run() {
                Random rnd = new Random(seed);
                Random rnd2 = new Random(21451328712L);
                byte[] bytes = new byte[1000];

                try {
                    while (true) {
                        if (rnd2.nextInt(100) < 1) {
                            // Read one byte and verify.
                            int c = mIn.read();
                            int expected = rnd.nextInt() & 0xff;
                            assertTrue(expected == c);
                        } else {
                            // Read and verify a chunk.
                            int size = rnd2.nextInt(1000);
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
                }
            }
        };

        reader.start();

        // Write random data, to be verified by reader.

        Random rnd = new Random(seed);
        Random rnd2 = new Random(21451328712L);
        byte[] sentBytes = new byte[1000];

        for (int q=0; q<STREAM_TEST_COUNT; q++) {
            if (rnd2.nextInt(100) < 1) {
                // Write one byte.
                int b = (byte) rnd.nextInt() & 0xff;
                mOut.write(b);
            } else {
                // Write a chunk.
                int size = rnd2.nextInt(1000);
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
            }
        }

        reader.interrupt();
        reader.join();
    }
}
