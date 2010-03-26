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
import java.io.ObjectInput;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class PipeInputStream extends InputStream implements ObjectInput {
    final ObjectInput mIn;

    PipeInputStream(ObjectInput in) {
        mIn = in;
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        mIn.readFully(b);
    }

    @Override
    public void readFully(byte[] b, int offset, int length) throws IOException {
        mIn.readFully(b, offset, length);
    }

    @Override
    public int skipBytes(int n) throws IOException {
        return mIn.skipBytes(n);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return mIn.readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
        return mIn.readByte();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return mIn.readUnsignedByte();
    }

    @Override
    public short readShort() throws IOException {
        return mIn.readShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return mIn.readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return mIn.readChar();
    }

    @Override
    public int readInt() throws IOException {
        return mIn.readInt();
    }

    @Override
    public long readLong() throws IOException {
        return mIn.readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return mIn.readFloat();
    }

    @Override
    public double readDouble() throws IOException {
        return mIn.readDouble();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    @Deprecated
    public String readLine() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readUTF() throws IOException {
        return mIn.readUTF();
    }

    @Override
    public Object readObject() throws IOException, ClassNotFoundException {
        return mIn.readObject();
    }

    @Override
    public int read() throws IOException {
        return mIn.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return mIn.read(b);
    }

    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        return mIn.read(b, offset, length);
    }

    @Override
    public long skip(long n) throws IOException {
        return mIn.skip(n);
    }

    @Override
    public int available() throws IOException {
        return mIn.available();
    }

    @Override
    public void close() throws IOException {
        mIn.close();
    }
}
