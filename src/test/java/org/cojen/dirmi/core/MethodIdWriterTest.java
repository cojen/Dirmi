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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.UnimplementedException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class MethodIdWriterTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(MethodIdWriterTest.class.getName());
    }

    @Test
    public void empty() throws Exception {
        // Note: It doesn't make sense to use the MethodIdWriterMaker when both infos are the
        // same, but it should still work.

        RemoteInfo info = RemoteInfo.examine(Remote.class);
        MethodIdWriter writer = MethodIdWriterMaker.writerFor(info, info, true);

        try {
            writer.writeMethodId(null, 0);
            fail();
        } catch (UnimplementedException e) {
        }
    }

    @Test
    public void equal() throws Exception {
        // Note: It doesn't make sense to use the MethodIdWriterMaker when both infos are the
        // same, but it should still work.

        RemoteInfo info = RemoteInfo.examine(A.class);
        MethodIdWriter writer = MethodIdWriterMaker.writerFor(info, info, true);

        var bout = new ByteArrayOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), bout);

        for (int i=0; i<3; i++) {
            writer.writeMethodId(pipe, i);
        }

        try {
            writer.writeMethodId(pipe, 4);
            fail();
        } catch (UnimplementedException e) {
        }

        pipe.flush();

        byte[] bytes = bout.toByteArray();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        for (int i=0; i<3; i++) {
            assertEquals(i, pipe.readUnsignedByte());
        }

        assertTrue(pipe.read() < 0);
    }

    @Test
    public void mismatch1() throws Exception {
        RemoteInfo original = RemoteInfo.examine(A.class);
        RemoteInfo current = RemoteInfo.examine(Remote.class);
        MethodIdWriter writer = MethodIdWriterMaker.writerFor(original, current, true);

        for (int i=0; i<3; i++) {
            try {
                writer.writeMethodId(null, i);
                fail();
            } catch (UnimplementedException e) {
            }
        }
    }

    @Test
    public void mismatch2() throws Exception {
        RemoteInfo original = RemoteInfo.examine(A.class);
        RemoteInfo current = RemoteInfo.examine(B.class);
        MethodIdWriter writer = MethodIdWriterMaker.writerFor(original, current, true);

        var bout = new ByteArrayOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), bout);

        writer.writeMethodId(pipe, 0);
        writer.writeMethodId(pipe, 1);

        try {
            writer.writeMethodId(pipe, 2);
            fail();
        } catch (UnimplementedException e) {
        }

        pipe.flush();

        byte[] bytes = bout.toByteArray();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        assertEquals(1, pipe.readUnsignedByte());
        assertEquals(2, pipe.readUnsignedByte());

        assertTrue(pipe.read() < 0);
    }

    @Test
    public void mismatch3() throws Exception {
        RemoteInfo original = RemoteInfo.examine(A.class);
        RemoteInfo current = RemoteInfo.examine(C.class);
        MethodIdWriter writer = MethodIdWriterMaker.writerFor(original, current, true);

        var bout = new ByteArrayOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), bout);

        writer.writeMethodId(pipe, 1);

        try {
            writer.writeMethodId(pipe, 0);
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("Unimplemented"));
        }

        try {
            writer.writeMethodId(pipe, 2);
            fail();
        } catch (UnimplementedException e) {
            assertTrue(e.getMessage().contains("Unimplemented"));
        }

        pipe.flush();

        byte[] bytes = bout.toByteArray();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        assertEquals(1, pipe.readUnsignedByte());

        assertTrue(pipe.read() < 0);
    }


    public static interface A extends Remote {
        void b() throws RemoteException;
        void d() throws RemoteException;
        void f() throws RemoteException;
    }

    public static interface B extends Remote {
        void a() throws RemoteException;
        void b() throws RemoteException;
        void d() throws RemoteException;
        void e() throws RemoteException;
    }

    public static interface C extends Remote {
        void c() throws RemoteException;
        void d() throws RemoteException;
    }
}
