/*
 *  Copyright 2007 Brian S O'Neill
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

package dirmi.core;

import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class AbstractInvocationChannel implements InvocationChannel {
    public void reset() throws IOException {
        getOutputStream().reset();
    }

    public void readFully(byte[] b) throws IOException {
        getInputStream().readFully(b);
    }

    public void readFully(byte[] b, int off, int len) throws IOException {
        getInputStream().readFully(b, off, len);
    }

    public int skipBytes(int n) throws IOException {
        return getInputStream().skipBytes(n);
    }

    public boolean readBoolean() throws IOException {
        return getInputStream().readBoolean();
    }

    public byte readByte() throws IOException {
        return getInputStream().readByte();
    }

    public int readUnsignedByte() throws IOException {
        return getInputStream().readUnsignedByte();
    }

    public short readShort() throws IOException {
        return getInputStream().readShort();
    }

    public int readUnsignedShort() throws IOException {
        return getInputStream().readUnsignedShort();
    }

    public char readChar() throws IOException {
        return getInputStream().readChar();
    }

    public int readInt() throws IOException {
        return getInputStream().readInt();
    }

    public long readLong() throws IOException {
        return getInputStream().readLong();
    }

    public float readFloat() throws IOException {
        return getInputStream().readFloat();
    }

    public double readDouble() throws IOException {
        return getInputStream().readDouble();
    }

    public String readLine() throws IOException {
        return getInputStream().readLine();
    }

    public String readUTF() throws IOException {
        return getInputStream().readUTF();
    }

    public Object readObject() throws ClassNotFoundException, IOException {
        return getInputStream().readObject();
    }

    public int read() throws IOException {
        return getInputStream().read();
    }

    public int read(byte[] b) throws IOException {
        return getInputStream().read(b);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return getInputStream().read(b, off, len);
    }

    public long skip(long n) throws IOException {
        return getInputStream().skip(n);
    }

    public int available() throws IOException {
        return getInputStream().available();
    }

    public void write(int b) throws IOException {
        getOutputStream().write(b);
    }

    public void write(byte[] b) throws IOException {
        getOutputStream().write(b);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        getOutputStream().write(b, off, len);
    }

    public void writeBoolean(boolean v) throws IOException {
        getOutputStream().writeBoolean(v);
    }

    public void writeByte(int v) throws IOException {
        getOutputStream().writeByte(v);
    }

    public void writeShort(int v) throws IOException {
        getOutputStream().writeShort(v);
    }

    public void writeChar(int v) throws IOException {
        getOutputStream().writeChar(v);
    }

    public void writeInt(int v) throws IOException {
        getOutputStream().writeInt(v);
    }

    public void writeLong(long v) throws IOException {
        getOutputStream().writeLong(v);
    }

    public void writeFloat(float v) throws IOException {
        getOutputStream().writeFloat(v);
    }

    public void writeDouble(double v) throws IOException {
        getOutputStream().writeDouble(v);
    }

    public void writeBytes(String s) throws IOException {
        getOutputStream().writeBytes(s);
    }

    public void writeChars(String s) throws IOException {
        getOutputStream().writeChars(s);
    }

    public void writeUTF(String str) throws IOException {
        getOutputStream().writeUTF(str);
    }

    public void writeObject(Object obj) throws IOException {
        getOutputStream().writeObject(obj);
    }

    public void flush() throws IOException {
        getOutputStream().flush();
    }
}
