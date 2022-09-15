/*
 *  Copyright 2006-2022 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.InterruptedIOException;

import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.ClosedException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class PipedStreamsTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(PipedStreamsTest.class.getName());
    }

    private static final int STREAM_TEST_COUNT = 100000;

    PipedInputStream mIn;
    PipedOutputStream mOut;

    @Before
    public void setUp() throws Exception {
        mIn = new PipedInputStream();
        assertTrue(mIn.toString().contains("unconnected"));
        mOut = new PipedOutputStream(mIn);
        assertTrue(mIn.toString().contains("connected to"));
        assertTrue(mOut.toString().contains("connected to"));
        assertFalse(mIn.isClosed());
        assertFalse(mOut.isClosed());
    }

    @After
    public void tearDown() throws Exception {
        mIn.close();
        mOut.close();
        assertTrue(mIn.isClosed());
        assertTrue(mOut.isClosed());
    }

    @Test
    public void halfClosed() throws Exception {
        mOut.close();
        assertEquals(-1, mIn.read());
        assertEquals(-1, mIn.read(new byte[10]));
        assertEquals(0, mIn.skip(10));
        assertEquals(0, mIn.available());
        mIn.close();
        try {
            mIn.read();
            fail();
        } catch (ClosedException e) {
        }
        try {
            mIn.read(new byte[10]);
            fail();
        } catch (ClosedException e) {
        }
        try {
            mIn.skip(10);
            fail();
        } catch (ClosedException e) {
        }
        try {
            mIn.available();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void doubleConnect() throws Exception {
        try {
            new PipedInputStream(mOut);
            fail();
        } catch (Exception e) {
        }
        try {
            new PipedOutputStream(mIn);
            fail();
        } catch (Exception e) {
        }
    }

    @Test
    public void skip() throws Exception {
        assertEquals(0, mIn.skip(0));

        var writer = new Thread() {
            public void run() {
                try {
                    mOut.write(new byte[10]);
                    mOut.write(new byte[100]);
                } catch (Exception e) {
                }
            }
        };

        writer.start();

        for (int i=0; i<10; i++) {
            if (mIn.available() != 0) {
                break;
            }
            Thread.sleep(500);
        }

        assertEquals(10, mIn.available());
        assertEquals(5, mIn.skip(5));
        assertEquals(10, mIn.skip(10));
        assertEquals(95, mIn.skip(95));
        assertEquals(0, mIn.available());

        writer.join();
    }

    @Test
    public void interruptWriter() throws Exception {
        Thread t = Thread.currentThread();

        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }
            t.interrupt();
        }).start();

        try {
            mOut.write(1);
        } catch (InterruptedIOException e) {
        }
    }

    @Test
    public void fuzz() throws Exception {
        final long seed = 342487102938L;

        var reader = new Thread() {
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
