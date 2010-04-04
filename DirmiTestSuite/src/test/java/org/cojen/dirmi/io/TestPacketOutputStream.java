/*
 *  Copyright 2009-2010 Brian S O'Neill
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.ClosedException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestPacketOutputStream {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestPacketOutputStream.class.getName());
    }

    @Test
    public void empty() throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Writer pout = new Writer(bout);
        Writer recycled = pout.recycle();
        assertFalse(pout == recycled);
        assertTrue(recycled != null);

        byte[] bytes = bout.toByteArray();
        assertEquals(1, bytes.length);
        assertEquals(0, bytes[0]);

        assertNull(pout.recycle());

        try {
            pout.write(0);
            fail();
        } catch (ClosedException e) {
        }

        recycled = recycled.recycle();
        assertTrue(recycled != null);

        bytes = bout.toByteArray();
        assertEquals(2, bytes.length);
        assertEquals(0, bytes[0]);
        assertEquals(0, bytes[1]);
    }

    @Test
    public void one() throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Writer pout = new Writer(bout);
        pout.write(10);
        PacketOutputStream recycled = pout.recycle();
        assertFalse(pout == recycled);
        assertTrue(recycled != null);

        byte[] bytes = bout.toByteArray();
        assertEquals(1 + 1 + 1, bytes.length);
        assertEquals(1, bytes[0]);
        assertEquals(10, bytes[1]);
        assertEquals(0, bytes[2]);
    }

    @Test
    public void two() throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Writer pout = new Writer(bout);
        pout.write(new byte[] {34, 78});
        PacketOutputStream recycled = pout.recycle();
        assertFalse(pout == recycled);
        assertTrue(recycled != null);

        byte[] bytes = bout.toByteArray();
        assertEquals(1 + 2 + 1, bytes.length);
        assertEquals(2, bytes[0]);
        assertEquals(34, bytes[1]);
        assertEquals(78, bytes[2]);
        assertEquals(0, bytes[3]);
    }

    @Test
    public void many() throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Writer pout = new Writer(bout);

        byte[] data = new byte[200];
        new Random().nextBytes(data);
        pout.write(data);

        PacketOutputStream recycled = pout.recycle();
        assertFalse(pout == recycled);
        assertTrue(recycled != null);

        byte[] bytes = bout.toByteArray();
        assertEquals(2 + 200 + 1, bytes.length);
        assertEquals(0x80, bytes[0] & 0xff);
        assertEquals(200 - 128, bytes[1]);
        assertEquals(0, bytes[bytes.length - 1]);

        for (int i=0; i<200; i++) {
            assertEquals(data[i], bytes[i + 2]);
        }
    }

    @Test
    public void randomBuffering() throws Exception {
        // Seeds triggered a bug, so keep using them.
        randomBuffering(1262037188096L, 258214034288994L);
    }

    @Test
    public void randomBuffering2() throws Throwable {
        long dataSeed = System.currentTimeMillis();
        long sizeSeed = System.nanoTime();
        try {
            randomBuffering(dataSeed, sizeSeed);
        } catch (Throwable e) {
            System.out.println("Data seed: " + dataSeed);
            System.out.println("Size seed: " + sizeSeed);
            throw e;
        }
    }

    public void randomBuffering(long dataSeed, long sizeSeed) throws Exception {
        Random dataSource = new Random(dataSeed);

        PipedOutputStream out = new PipedOutputStream();
        Reader reader = new Reader(out, dataSeed);
        Thread t = new Thread(reader);
        t.start();

        Writer pout = new Writer(out, 8192);

        Random sizeSource = new Random(sizeSeed);

        long total = 0;
        try {
            for (int i=0; i<10000; i++) {
                if (sizeSource.nextInt(100) == 0) {
                    pout.write(dataSource.nextInt());
                    total++;
                } else {
                    int size = sizeSource.nextInt(10000) + 1;
                    byte[] data = new byte[size];
                    for (int j=0; j<size; j++) {
                        data[j] = (byte) dataSource.nextInt();
                    }
                    pout.write(data);
                    total += size;
                }
            }
        } catch (IOException e) {
            assertEquals(null, reader.mFailed);
            throw e;
        }

        pout.flush();
        PacketOutputStream recycled = pout.recycle();

        t.join(10000);

        assertEquals(null, reader.mFailed);
        assertEquals(total, reader.mTotal);

        assertFalse(pout == recycled);
        assertTrue(recycled != null);
    }

    static class Writer extends PacketOutputStream<Writer> {
        private volatile Writer mRecycled;

        Writer(OutputStream out) {
            super(out);
        }

        Writer(OutputStream out, int size) {
            super(out, size);
        }

        private Writer() {
        }

        @Override
        protected Writer newInstance() {
            return new Writer();
        }

        @Override
        protected void recycled(Writer newInstance) {
            mRecycled = newInstance;
        }

        Writer recycle() throws IOException {
            mRecycled = null;
            close();
            return mRecycled;
        }

        Writer getRecycled() {
            return mRecycled;
        }
    }

    private static class Reader extends PipedInputStream implements Runnable {
        private final Random mDataSource;

        volatile long mTotal;
        volatile IOException mFailed;

        Reader(PipedOutputStream out, long seed) throws IOException {
            super(out);
            mDataSource = new Random(seed);
        }

        public void run() {
            try {
                mTotal = drain();
            } catch (IOException e) {
                mFailed = e;
                close();
            }
        }

        private long drain() throws IOException {
            long total = 0;

            while (true) {
                int size = read();
                if (size >= 0x80) {
                    size = (((size & 0x7f) << 8) | (read() & 0xff)) + 0x80;
                }

                if (size == 0) {
                    return total;
                }

                total += size;

                for (int i=0; i<size; i++) {
                    int b = read();
                    int expected = mDataSource.nextInt() & 0xff;
                    if (b != expected) {
                        throw new IOException("" + expected + " != " + b);
                    }
                }
            }
        }
    }
}
