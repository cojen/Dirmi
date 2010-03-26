/*
 *  Copyright 2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.Pipe;

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class WrappedPipe implements Pipe {
    /**
     * Called immediately (and only) before every read.
     */
    abstract Pipe readPipe() throws IOException;

    /**
     * Called immediately (and only) before every write.
     */
    abstract Pipe writePipe() throws IOException;

    /**
     * Called only for non-read or non-write operations.
     */
    abstract Pipe anyPipe() throws IOException;

    private final InputStream mIn;
    private final OutputStream mOut;

    WrappedPipe() {
        mIn = new PipeInputStream(this);
        mOut = new PipeOutputStream(this);
    }

    @Override
    public Object getLocalAddress() {
        try {
            return anyPipe().getLocalAddress();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public Object getRemoteAddress() {
        try {
            return anyPipe().getRemoteAddress();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public boolean startTimeout(long timeout, TimeUnit unit) throws IOException {
        return anyPipe().startTimeout(timeout, unit);
    }

    @Override
    public boolean cancelTimeout() {
        try {
            return anyPipe().cancelTimeout();
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public InputStream getInputStream() {
        return mIn;
    }

    @Override
    public OutputStream getOutputStream() {
        return mOut;
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        readPipe().readFully(b);
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        readPipe().readFully(b, off, len);
    }

    @Override
    public int skipBytes(int n) throws IOException {
        return readPipe().skipBytes(n);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readPipe().readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
        return readPipe().readByte();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return readPipe().readUnsignedByte();
    }

    @Override
    public short readShort() throws IOException {
        return readPipe().readShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return readPipe().readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return readPipe().readChar();
    }

    @Override
    public int readInt() throws IOException {
        return readPipe().readInt();
    }

    @Override
    public long readLong() throws IOException {
        return readPipe().readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return readPipe().readFloat();
    }

    @Override
    public double readDouble() throws IOException {
        return readPipe().readDouble();
    }

    @Override
    @Deprecated
    public String readLine() throws IOException {
        return readPipe().readLine();
    }

    @Override
    public String readUTF() throws IOException {
        return readPipe().readUTF();
    }

    @Override
    public Throwable readThrowable() throws IOException {
        return readPipe().readThrowable();
    }

    @Override
    public Object readObject() throws ClassNotFoundException, IOException {
        return readPipe().readObject();
    }

    @Override
    public int read() throws IOException {
        return readPipe().read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return readPipe().read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return readPipe().read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return readPipe().skip(n);
    }

    @Override
    public int available() throws IOException {
        return readPipe().available();
    }

    @Override
    public void write(int b) throws IOException {
        writePipe().write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        writePipe().write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        writePipe().write(b, off, len);
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        writePipe().writeBoolean(v);
    }

    @Override
    public void writeByte(int v) throws IOException {
        writePipe().writeByte(v);
    }

    @Override
    public void writeShort(int v) throws IOException {
        writePipe().writeShort(v);
    }

    @Override
    public void writeChar(int v) throws IOException {
        writePipe().writeChar(v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        writePipe().writeInt(v);
    }

    @Override
    public void writeLong(long v) throws IOException {
        writePipe().writeLong(v);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        writePipe().writeFloat(v);
    }

    @Override
    public void writeDouble(double v) throws IOException {
        writePipe().writeDouble(v);
    }

    @Override
    public void writeBytes(String s) throws IOException {
        writePipe().writeBytes(s);
    }

    @Override
    public void writeChars(String s) throws IOException {
        writePipe().writeChars(s);
    }

    @Override
    public void writeUTF(String str) throws IOException {
        writePipe().writeUTF(str);
    }

    @Override
    public void writeObject(Object obj) throws IOException {
        writePipe().writeObject(obj);
    }

    @Override
    public void writeThrowable(Throwable t) throws IOException {
        writePipe().writeThrowable(t);
    }

    @Override
    public void reset() throws IOException {
        writePipe().reset();
    }

    @Override
    public void flush() throws IOException {
        writePipe().flush();
    }

    @Override
    public String toString() {
        try {
            return anyPipe().toString();
        } catch (IOException e) {
            return super.toString();
        }
    }
}
