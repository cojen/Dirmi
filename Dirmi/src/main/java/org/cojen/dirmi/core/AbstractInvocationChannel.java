/*
 *  Copyright 2007-2010 Brian S O'Neill
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

import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class AbstractInvocationChannel implements InvocationChannel {
    final InvocationInputStream mInvIn;
    final InvocationOutputStream mInvOut;

    AbstractInvocationChannel(ObjectInputStream in, DrainableObjectOutputStream out) {
        mInvIn = new InvocationInputStream(this, in);
        mInvOut = new InvocationOutputStream(this, out);
    }

    /**
     * Copy constructor which allows input to be replaced.
     */
    AbstractInvocationChannel(AbstractInvocationChannel chan, ObjectInputStream in) {
        mInvIn = new InvocationInputStream(this, in);
        mInvOut = chan.mInvOut;
    }

    public final InvocationInputStream getInputStream() {
        return mInvIn;
    }

    public final InvocationOutputStream getOutputStream() {
        return mInvOut;
    }

    public void reset() throws IOException {
        getOutputStream().reset();
    }

    public void readFully(byte[] b) throws IOException {
        mInvIn.readFully(b);
    }

    public void readFully(byte[] b, int off, int len) throws IOException {
        mInvIn.readFully(b, off, len);
    }

    public int skipBytes(int n) throws IOException {
        return mInvIn.skipBytes(n);
    }

    public boolean readBoolean() throws IOException {
        return mInvIn.readBoolean();
    }

    public byte readByte() throws IOException {
        return mInvIn.readByte();
    }

    public int readUnsignedByte() throws IOException {
        return mInvIn.readUnsignedByte();
    }

    public short readShort() throws IOException {
        return mInvIn.readShort();
    }

    public int readUnsignedShort() throws IOException {
        return mInvIn.readUnsignedShort();
    }

    public char readChar() throws IOException {
        return mInvIn.readChar();
    }

    public int readInt() throws IOException {
        return mInvIn.readInt();
    }

    public long readLong() throws IOException {
        return mInvIn.readLong();
    }

    public float readFloat() throws IOException {
        return mInvIn.readFloat();
    }

    public double readDouble() throws IOException {
        return mInvIn.readDouble();
    }

    public String readLine() throws IOException {
        return mInvIn.readLine();
    }

    public String readUTF() throws IOException {
        return mInvIn.readUTF();
    }

    public Object readObject() throws ClassNotFoundException, IOException {
        return mInvIn.readObject();
    }

    public int read() throws IOException {
        return mInvIn.read();
    }

    public int read(byte[] b) throws IOException {
        return mInvIn.read(b);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return mInvIn.read(b, off, len);
    }

    public long skip(long n) throws IOException {
        return mInvIn.skip(n);
    }

    public int available() throws IOException {
        return mInvIn.available();
    }

    public void write(int b) throws IOException {
        mInvOut.write(b);
    }

    public void write(byte[] b) throws IOException {
        mInvOut.write(b);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        mInvOut.write(b, off, len);
    }

    public void writeBoolean(boolean v) throws IOException {
        mInvOut.writeBoolean(v);
    }

    public void writeByte(int v) throws IOException {
        mInvOut.writeByte(v);
    }

    public void writeShort(int v) throws IOException {
        mInvOut.writeShort(v);
    }

    public void writeChar(int v) throws IOException {
        mInvOut.writeChar(v);
    }

    public void writeInt(int v) throws IOException {
        mInvOut.writeInt(v);
    }

    public void writeLong(long v) throws IOException {
        mInvOut.writeLong(v);
    }

    public void writeFloat(float v) throws IOException {
        mInvOut.writeFloat(v);
    }

    public void writeDouble(double v) throws IOException {
        mInvOut.writeDouble(v);
    }

    public void writeBytes(String s) throws IOException {
        mInvOut.writeBytes(s);
    }

    public void writeChars(String s) throws IOException {
        mInvOut.writeChars(s);
    }

    public void writeUTF(String str) throws IOException {
        mInvOut.writeUTF(str);
    }

    public void writeObject(Object obj) throws IOException {
        mInvOut.writeObject(obj);
    }

    public void flush() throws IOException {
        mInvOut.flush();
    }
}
