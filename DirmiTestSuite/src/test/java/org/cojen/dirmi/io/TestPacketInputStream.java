/*
 *  Copyright 2009 Brian S O'Neill
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;

import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.ClosedException;

import org.cojen.dirmi.util.ThreadPool;

import static org.cojen.dirmi.AbstractTestSuite.sleep;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestPacketInputStream {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestPacketInputStream.class.getName());
    }

    private static volatile ThreadPool threadPool;
    private static volatile IOExecutor executor;

    @BeforeClass
    public static void before() {
        threadPool = new ThreadPool(1000, true);
        executor = new IOExecutor(threadPool);
    }

    @AfterClass
    public static void after() {
        ThreadPool pool = threadPool;
        threadPool = null;
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    public void empty() throws Exception {
        ByteArrayInputStream bin = new ByteArrayInputStream(new byte[] {0, 0});
        Reader pin = new Reader(bin);
        assertEquals(-1, pin.read());
        Reader recycled = pin.recycle();
        assertFalse(pin == recycled);
        assertTrue(recycled != null);

        assertNull(pin.recycle());

        try {
            pin.read();
            fail();
        } catch (ClosedException e) {
        }

        assertEquals(-1, recycled.read());
        recycled = recycled.recycle();
        assertTrue(recycled != null);
    }

    @Test
    public void one() throws Exception {
        ByteArrayInputStream bin = new ByteArrayInputStream(new byte[] {1, 10, 0});
        Reader pin = new Reader(bin);
        assertEquals(10, pin.read());
        assertEquals(-1, pin.read());
        PacketInputStream recycled = pin.recycle();
        assertFalse(pin == recycled);
        assertTrue(recycled != null);
    }

    @Test
    public void oneAsync() throws Exception {
        ByteArrayInputStream bin = new ByteArrayInputStream(new byte[] {1, 10, 0});
        Reader pin = new Reader(bin);
        assertEquals(10, pin.read());
        PacketInputStream recycled = pin.recycleAndWait();
        assertFalse(pin == recycled);
        assertTrue(recycled != null);
        assertTrue(pin.didUseExecutor());
    }

    @Test
    public void two() throws Exception {
        ByteArrayInputStream bin = new ByteArrayInputStream(new byte[] {2, 34, 78, 0});
        Reader pin = new Reader(bin);
        assertEquals(34, pin.read());
        assertEquals(78, pin.read());
        assertEquals(-1, pin.read());
        PacketInputStream recycled = pin.recycle();
        assertFalse(pin == recycled);
        assertTrue(recycled != null);
    }

    @Test
    public void twoAsync() throws Exception {
        twoAsync(2);
    }

    @Test
    public void twoAsyncAbort() throws Exception {
        twoAsync(1);
    }

    private void twoAsync(int limit) throws Exception {
        ByteArrayInputStream bin = new ByteArrayInputStream(new byte[] {2, 34, 78, 0});
        Reader pin = new Reader(bin);
        if (--limit >= 0) {
            assertEquals(34, pin.read());
            if (--limit >= 0) {
                assertEquals(78, pin.read());
            }
        }
        PacketInputStream recycled = pin.recycleAndWait();
        assertFalse(pin == recycled);
        assertTrue(recycled != null);
        assertTrue(pin.didUseExecutor());
    }

    @Test
    public void many() throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        PacketOutputStream pout = new TestPacketOutputStream.Writer(bout);

        byte[] data = new byte[200];
        new Random().nextBytes(data);
        pout.write(data);
        pout.close();

        ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
        Reader pin = new Reader(bin);

        for (int i=0; i<200; i++) {
            assertEquals(data[i] & 0xff, pin.read());
        }
    }

    @Test
    public void manyAsyncAbort1() throws Exception {
        manyAsyncAbort(200);
    }

    @Test
    public void manyAsyncAbort2() throws Exception {
        manyAsyncAbort(100);
    }

    @Test
    public void manyAsyncAbort3() throws Exception {
        manyAsyncAbort(0);
    }

    private void manyAsyncAbort(int limit) throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        PacketOutputStream pout = new TestPacketOutputStream.Writer(bout);

        byte[] data = new byte[200];
        new Random().nextBytes(data);
        pout.write(data);
        pout.close();

        // Manually apply empty packet header, since PacketOutputStream did not
        // have a recycler.
        byte[] data2 = bout.toByteArray();
        byte[] data3 = new byte[data2.length + 1];
        System.arraycopy(data2, 0, data3, 0, data2.length);
        data3[data3.length - 1] = 0;

        ByteArrayInputStream bin = new ByteArrayInputStream(data3);
        Reader pin = new Reader(bin);

        for (int i=0; i<limit; i++) {
            assertEquals(data[i] & 0xff, pin.read());
        }

        PacketInputStream recycled = pin.recycleAndWait();

        assertFalse(pin == recycled);
        assertTrue(recycled != null);
        assertTrue(pin.didUseExecutor());
    }

    @Test
    public void recyclingChaos() throws Throwable {
        long seed = System.nanoTime();
        try {
            recyclingChaos(seed);
        } catch (Throwable e) {
            System.out.println("Seed: " + seed);
            throw e;
        }
    }

    private void recyclingChaos(long seed) throws Exception {
        final PipedInputStream masterInput = new PipedInputStream();
        final PipedOutputStream masterOutput = new PipedOutputStream(masterInput);

        final int maxTotal = 1000000;

        Reader pin = new Reader(masterInput);
        TestPacketOutputStream.Writer pout = new TestPacketOutputStream.Writer(masterOutput);

        Random inSizeRandom = new Random(seed - 1);

        for (int i=0; i<100; i++) {
            seed++;

            final long fseed = seed;
            final TestPacketOutputStream.Writer fpout = pout;

            class WriterThread extends Thread {
                public void run() {
                    Random outRandom = new Random(fseed);
                    Random sizeRandom = new Random(fseed + 1);

                    try {
                        int total = outRandom.nextInt(maxTotal);
                        while (total > 0) {
                            if (sizeRandom.nextInt(100) == 0) {
                                fpout.write(outRandom.nextInt());
                                total--;
                            } else {
                                int size = sizeRandom.nextInt(1000) + 1;
                                if (size > total) {
                                    size = total;
                                }
                                byte[] data = new byte[size];
                                for (int j=0; j<size; j++) {
                                    data[j] = (byte) outRandom.nextInt();
                                }
                                fpout.write(data);
                                total -= size;
                            }
                        }
                        fpout.close();
                    } catch (ClosedException e) {
                        // Expected at times.
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            };

            WriterThread writerThread = new WriterThread();
            writerThread.start();

            final Random inRandom = new Random(seed);
            final int expectedTotal = inRandom.nextInt(maxTotal);

            int abortAt = Integer.MAX_VALUE;
            if (inSizeRandom.nextBoolean()) {
                if (inSizeRandom.nextBoolean()) {
                    abortAt = expectedTotal;
                } else {
                    abortAt = expectedTotal - inSizeRandom.nextInt(maxTotal);
                    if (abortAt < 0) {
                        abortAt = 0;
                    }
                }
            }

            int total = 0;
            while (total < abortAt) {
                if (inSizeRandom.nextInt(100) == 0) {
                    int b = pin.read();
                    if (b < 0) {
                        break;
                    }
                    assertEquals(inRandom.nextInt() & 0xff, b);
                    total++;
                } else {
                    int size = inSizeRandom.nextInt(1000) + 1;
                    if (abortAt == expectedTotal && total + size > expectedTotal) {
                        size = expectedTotal - total;
                    }
                    byte[] data = new byte[size];
                    int amt = pin.read(data);
                    if (amt <= 0) {
                        break;
                    }
                    for (int j=0; j<amt; j++) {
                        assertEquals(inRandom.nextInt() & 0xff, data[j] & 0xff);
                    }
                    total += amt;
                }
            }

            pin.recycle();

            if (abortAt == Integer.MAX_VALUE) {
                assertEquals(expectedTotal, total);
            } else {
                fpout.close();
            }

            pin = pin.waitForRecycled();

            writerThread.join();

            pout = fpout.getRecycled();
        }
    }

    private static class Reader extends PacketInputStream<Reader> {
        private volatile boolean mUsedExecutor;
        private Reader mRecycled;

        Reader(InputStream in) {
            super(in);
        }

        Reader(InputStream in, int size) {
            super(in, size);
        }

        private Reader() {
        }

        @Override
        protected IOExecutor executor() {
            mUsedExecutor = true;
            return executor;
        }

        @Override
        protected Reader newInstance() {
            return new Reader(this);
        }

        @Override
        protected synchronized void recycled(Reader newInstance) {
            mRecycled = newInstance;
            notifyAll();
        }

        Reader recycle() throws IOException {
            synchronized (this) {
                mRecycled = null;
            }
            close();
            synchronized (this) {
                return mRecycled;
            }
        }

        Reader recycleAndWait() throws IOException, InterruptedException {
            recycle();
            return waitForRecycled();
        }

        Reader waitForRecycled() throws InterruptedException {
            long end = System.nanoTime() + 10 * 1000000000L;
            synchronized (this) {
                do {
                    if (mRecycled != null) {
                        break;
                    }
                    wait(10000);
                    if (mRecycled != null) {
                        break;
                    }
                } while (System.nanoTime() < end);
                return mRecycled;
            }
        }

        boolean didUseExecutor() {
            return mUsedExecutor;
        }
    }
}
