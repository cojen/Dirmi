/*
 *  Copyright 2022 Cojen.org
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
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.OutputStream;

import org.cojen.dirmi.Serializer;

import org.cojen.dirmi.io.CaptureOutputStream;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class CorruptSerializerTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(CorruptSerializerTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        Serializer<PointRec> s = Serializer.simple(PointRec.class);
        assertSame(s, Serializer.simple(PointRec.class));

        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        s.write(pipe, new PointRec(1, 2));
        pipe.flush();
        
        byte[] bytes = capture.getBytes();

        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());
        assertEquals(new PointRec(1, 2), s.read(pipe));

        // Change the serialized version.
        bytes[0] ^= bytes[0];

        bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());
        try {
            s.read(pipe);
            fail();
        } catch (InvalidClassException e) {
            assertTrue(e.getMessage().contains(PointRec.class.getName()));
            assertTrue(e.getMessage().contains("Serializer version mismatch"));
        }
    }

    public record PointRec(int x, int y) {}
}
