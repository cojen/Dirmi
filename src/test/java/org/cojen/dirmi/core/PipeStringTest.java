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

package org.cojen.dirmi.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
 
import java.util.Random;
 
import org.junit.*;
import static org.junit.Assert.*;
 
/**
 * Regression tests for string encoding/decoding at buffer boundaries, and for Class objects.
 * All tests are in-memory: the bytes are fully written first, and then read back from a
 * ByteArrayInputStream. A reader which asks for more bytes than were written fails with an
 * exception instead of blocking.
 *
 * @author Claude
 */
public class PipeStringTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(PipeStringTest.class.getName());
    }
 
    private static final int SENTINEL = 0x5eed5eed;
 
    /**
     * Writes the string followed by a sentinel int.
     */
    private static byte[] write(Object obj) throws Exception {
        var capture = new ByteArrayOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        pipe.writeObject(obj);
        pipe.writeInt(SENTINEL);
        pipe.flush();
        return capture.toByteArray();
    }
 
    private static BufferedPipe reader(byte[] bytes) {
        return new BufferedPipe(new ByteArrayInputStream(bytes), OutputStream.nullOutputStream());
    }
 
    /**
     * Verifies that the string can be read back, and that it can also be skipped. In both
     * cases the pipe must be positioned exactly at the sentinel afterwards.
     */
    private static void roundTrip(String str) throws Exception {
        byte[] bytes = write(str);
 
        BufferedPipe pipe = reader(bytes);
        assertEquals(str, pipe.readObject());
        assertEquals(SENTINEL, pipe.readInt());
 
        pipe = reader(bytes);
        pipe.skipObject(null);
        assertEquals(SENTINEL, pipe.readInt());
    }
 
    /**
     * An unpaired high surrogate at the end of a string (for example, when a string is cut
     * in the middle of an emoji by substring) was silently dropped by the encoder, which left
     * the encoded length too short. The decoder then blocked, or else consumed the bytes of
     * whatever followed.
     */
    @Test
    public void unpairedHighSurrogateAtEnd() throws Exception {
        roundTrip("\ud83d");
        roundTrip("ab\ud83d");
        roundTrip("\u00e9\ud83d");
        roundTrip("x".repeat(300) + "\ud83d");        // long form string header
        roundTrip("x".repeat(20000) + "\ud83d");      // encoded in chunks
 
        // Cut an emoji in half. The result is followed by other data.
        String cut = "ab\ud83d\ude00".substring(0, 3);
        roundTrip(cut);
    }
 
    /**
     * Valid surrogate pairs, and unpaired surrogates in various positions, round trip.
     */
    @Test
    public void surrogates() throws Exception {
        roundTrip("\ud83d\ude00");
        roundTrip("\ud800a");
        roundTrip("\udc00");
        roundTrip("a\ude00\ud83d\ude00\ud83d");
        roundTrip("\ud83d\ud83d");
    }
 
    /**
     * The decoder added the extra bytes needed by a multi-byte character split across a buffer
     * boundary to its remaining length, and never removed them. The surplus accumulated, and
     * it eventually required more input than was available. These lengths and contents used
     * to fail, for both reading and skipping.
     */
    @Test
    public void multiByteCharsAcrossBufferBoundaries() throws Exception {
        for (int len : new int[] {2731, 8190, 8191, 8192, 8193, 10000, 16384, 20000, 65536}) {
            // Only 3-byte characters.
            roundTrip(generate(new Random(len * 31L + 3), len, 3));
            // Only surrogate code units, paired or not.
            roundTrip(generate(new Random(len * 31L + 5), len, 5));
            // A mix of everything.
            roundTrip(generate(new Random(len * 31L), len, 0));
        }
    }
 
    /**
     * @param mode 0 for a mix of everything, 3 for 3-byte characters only, 5 for surrogate
     * code units only
     */
    private static String generate(Random rnd, int len, int mode) {
        var b = new StringBuilder(len);
        while (b.length() < len) {
            int kind = mode == 0 ? rnd.nextInt(6) : mode;
            switch (kind) {
            case 0: b.append((char) ('a' + rnd.nextInt(26))); break;
            case 1: b.append((char) rnd.nextInt(128)); break;
            case 2: b.append((char) (0x80 + rnd.nextInt(0x780))); break;
            case 3: b.append((char) (0x800 + rnd.nextInt(0xd000 - 0x800))); break;
            case 4: b.appendCodePoint(0x10000 + rnd.nextInt(0x100000)); break;
            default: b.append((char) (0xd800 + rnd.nextInt(0x800))); break;
            }
        }
        return b.toString();
    }
}
