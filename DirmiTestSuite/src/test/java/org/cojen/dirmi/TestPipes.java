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

package org.cojen.dirmi;

import java.io.EOFException;
import java.io.IOException;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestPipes extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestPipes.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemotePipesServer());
    }

    @Test
    public void basic() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        assertFalse(pipes instanceof RemotePipesServer);

        Pipe pipe = pipes.basic(null);
        pipe.writeInt(123456789);
        pipe.flush();
        int i = pipe.readInt();
        assertEquals(123456789, i);
    }

    @Test
    public void echoBytes() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(byte[].class, null);
        byte[] bytes = "hello".getBytes();
        pipe.writeInt(bytes.length);
        pipe.write(bytes);
        pipe.flush();
        byte[] response = new byte[bytes.length];
        pipe.readFully(response);
        assertArrayEquals(bytes, response);
    }

    @Test
    public void echoBoolean() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(boolean.class, null);
        pipe.writeBoolean(false);
        pipe.flush();
        assertEquals(false, pipe.readBoolean());
        pipe = pipes.echo(boolean.class, null);
        pipe.writeBoolean(true);
        pipe.flush();
        assertEquals(true, pipe.readBoolean());
    }

    @Test
    public void echoByte() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(byte.class, null);
        pipe.writeByte(-99);
        pipe.flush();
        assertEquals(-99, pipe.readByte());
    }

    @Test
    public void echoShort() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(short.class, null);
        pipe.writeShort(-9999);
        pipe.flush();
        assertEquals(-9999, pipe.readShort());
    }

    @Test
    public void echoChar() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(char.class, null);
        pipe.writeChar('A');
        pipe.flush();
        assertEquals('A', pipe.readChar());
    }

    @Test
    public void echoInt() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(int.class, null);
        pipe.writeInt(-999999999);
        pipe.flush();
        assertEquals(-999999999, pipe.readInt());
    }

    @Test
    public void echoLong() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(long.class, null);
        pipe.writeLong(-999999999999999999L);
        pipe.flush();
        assertEquals(-999999999999999999L, pipe.readLong());
    }

    @Test
    public void echoFloat() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(float.class, null);
        pipe.writeFloat((float) Math.PI);
        pipe.flush();
        assertTrue((float) Math.PI == pipe.readFloat());
    }

    @Test
    public void echoDouble() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(double.class, null);
        pipe.writeDouble(Math.PI);
        pipe.flush();
        assertTrue(Math.PI == pipe.readDouble());
    }

    @Test
    public void echoUTF() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(String.class, null);
        pipe.writeUTF("hello\u1234");
        pipe.flush();
        assertEquals("hello\u1234", pipe.readUTF());
    }

    @Test
    public void echoThrowable() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(Throwable.class, null);
        Throwable obj = new IllegalArgumentException("illegal");
        pipe.writeThrowable(obj);
        pipe.flush();
        assertEquals(obj.toString(), pipe.readThrowable().toString());
    }

    @Test
    public void echoObject() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(Object.class, null);
        Object obj = new java.math.BigDecimal(Math.E);
        pipe.writeObject(obj);
        pipe.flush();
        assertEquals(obj, pipe.readObject());
    }

    @Test
    public void pipeClose() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(int.class, null);
        pipe.writeInt(50);
        pipe.flush();

        // Wait for remote close to reach local pipe.
        Thread.sleep(1000);

        assertEquals(50, pipe.readInt());

        // Pipe not locally closed, so returns EOF.
        assertEquals(-1, pipe.read());
        assertEquals(-1, pipe.read());
        try {
            pipe.readObject();
            fail();
        } catch (EOFException e) {
        }

        // After locally closing pipe, reads throw non-EOF exception.
        pipe.close();

        try {
            pipe.getInputStream().read();
            fail();
        } catch (EOFException e) {
            fail();
        } catch (IOException e) {
        }
        try {
            pipe.read();
            fail();
        } catch (EOFException e) {
            fail();
        } catch (IOException e) {
        }
        try {
            pipe.readObject();
            fail();
        } catch (EOFException e) {
            fail();
        } catch (IOException e) {
        }

        // Due to buffering in ObjectOutputStream, need to flush to force an
        // exception to be thrown.
        try {
            pipe.write(1);
            pipe.writeObject("hello");
            pipe.flush();
            fail();
        } catch (IOException e) {
        }
    }

    @Test
    public void pipeClose2() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(int.class, null);
        pipe.writeInt(50);
        pipe.flush();

        // Wait for remote close to reach local pipe.
        Thread.sleep(1000);

        assertEquals(50, pipe.readInt());

        pipe.write(1);
        try {
            pipe.flush();
        } catch (IOException e) {
        }
    }

    @Test
    public void pipeClose3() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echo(int.class, null);
        pipe.writeInt(50);
        pipe.flush();

        // Wait for remote close to reach local pipe.
        Thread.sleep(1000);

        assertEquals(50, pipe.readInt());

        try {
            // Write failure is not immediately detected with sockets, so write
            // a bunch of times to ensure send buffer is filled.
            for (int i=0; i<1000000; i++) {
                pipe.write(1);
            }
            fail();
        } catch (IOException e) {
        }

        // Failed writes force input to be closed too now.
        try {
            pipe.read();
            fail();
        } catch (IOException e) {
        }
    }

    @Test
    public void pipeClose4() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        Pipe pipe = pipes.echoNoClose(null, 50);

        assertEquals(50, pipe.readInt());

        // After locally closing pipe, reads throw non-EOF exception.
        pipe.close();

        // This behavior is completely wrong, but this is how ObjectInputStream
        // behaves. I call it a bug, but the object streams don't define their
        // close behavior.
        assertEquals(-1, pipe.read());

        try {
            pipe.readObject();
            fail();
        } catch (EOFException e) {
            fail();
        } catch (IOException e) {
        }

        // After throwing an exception, further reads appear to fail correctly.
        try {
            pipe.read();
            fail();
        } catch (EOFException e) {
            fail();
        } catch (IOException e) {
        }
    }

    @Test
    public void concurrentClose() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        final Pipe pipe = pipes.open(null);

        Thread reader = new Thread("pipe reader") {
            public void run() {
                try {
                    // This blocks because nothing is written.
                    pipe.read();
                } catch (Exception e) {
                }
            }
        };

        Thread closer = new Thread("pipe closer") {
            public void run() {
                try {
                    sleep(5000);
                } catch (InterruptedException e) {
                }
                try {
                    pipe.close();
                } catch (Exception e) {
                }
            }
        };

        reader.start();
        closer.start();

        for (int i=0; i<100; i++) {
            if (!reader.isAlive()) {
                return;
            }
            Thread.sleep(100);
        }

        fail("Close didn't interrupt blocked read");
    }

    @Test
    public void requestReply() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        setRequestValue(pipes, 10);

        {
            Pipe pipe = pipes.requestReply(null);
            pipe.writeInt(123);
            int reply = pipe.readInt();
            assertEquals(123 + 1, reply);
            pipe.close();
        }

        {
            Pipe pipe = pipes.requestReply(null);
            pipe.writeInt(123);
            pipe.readInt();
            try {
                pipe.writeInt(124);
                fail();
            } catch (IOException e) {
            }
            pipe.close();
        }

        {
            Pipe pipe = pipes.requestReply(null);
            pipe.writeInt(123);
            int reply = pipe.readInt();
            assertEquals(123 + 1, reply);
            assertEquals(-1, pipe.read());
            pipe.close();
            // Make sure that reading EOF doesn't mess up recycled channel.
            for (int i=0; i<10; i++) {
                assertEquals(10, pipes.getRequestValue());
            }
        }
    }

    private void setRequestValue(RemotePipes pipes, int value) throws Exception {
        pipes.setRequestValue(value);
        for (int i=0; i<10; i++) {
            if (pipes.getRequestValue() == value) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(value, pipes.getRequestValue());
    }

    @Test
    public void requestOnly() throws Exception {
        requestOnly(false);
    }

    @Test
    public void requestOnlyEOF() throws Exception {
        requestOnly(true);
    }

    private void requestOnly(boolean readEOF) throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        setRequestValue(pipes, 0);

        {
            Pipe pipe = pipes.requestOnly(null, readEOF);
            pipe.writeInt(123);
            pipe.close();
            int expect = readEOF ? -1 : 123;
            for (int i=0; i<10; i++) {
                if (pipes.getRequestValue() != expect) {
                    Thread.sleep(100);
                }
            }
            for (int i=0; i<10; i++) {
                assertEquals(expect, pipes.getRequestValue());
            }
        }

        {
            Pipe pipe = pipes.requestOnly(null, readEOF);
            pipe.close();
            for (int i=0; i<10; i++) {
                if (pipes.getRequestValue() != Integer.MIN_VALUE) {
                    Thread.sleep(100);
                }
            }
            assertEquals(Integer.MIN_VALUE, pipes.getRequestValue());
        }
    }

    @Test
    public void replyOnly() throws Exception {
        RemotePipes pipes = (RemotePipes) sessionStrategy.remoteServer;
        setRequestValue(pipes, 10);

        {
            Pipe pipe = pipes.replyOnly(null);
            int reply = pipe.readInt();
            assertEquals(111, reply);
            pipe.close();
        }

        {
            Pipe pipe = pipes.replyOnly(null);
            pipe.readInt();
            try {
                pipe.writeInt(124);
                fail();
            } catch (IOException e) {
            }
            pipe.close();
        }

        {
            Pipe pipe = pipes.replyOnly(null);
            int reply = pipe.readInt();
            assertEquals(111, reply);
            assertEquals(-1, pipe.read());
            pipe.close();
            // Make sure that reading EOF doesn't mess up recycled channel.
            for (int i=0; i<10; i++) {
                assertEquals(10, pipes.getRequestValue());
            }
        }
    }
}
