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

import java.io.IOException;
import java.io.ObjectOutput;
import java.io.OutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class PipeOutputStream extends OutputStream implements ObjectOutput {
    private final ObjectOutput mOut;

    PipeOutputStream(ObjectOutput out) {
        mOut = out;
    }

    @Override
    public void write(int b) throws IOException {
        mOut.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        mOut.write(b);
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
        mOut.write(b, offset, length);
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        mOut.writeBoolean(v);
    }

    @Override
    public void writeByte(int v) throws IOException {
        mOut.writeByte(v);
    }

    @Override
    public void writeShort(int v) throws IOException {
        mOut.writeShort(v);
    }

    @Override
    public void writeChar(int v) throws IOException {
        mOut.writeChar(v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        mOut.writeInt(v);
    }

    @Override
    public void writeLong(long v) throws IOException {
        mOut.writeLong(v);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        mOut.writeFloat(v);
    }

    @Override
    public void writeDouble(double v) throws IOException {
        mOut.writeDouble(v);
    }

    @Override
    public void writeBytes(String s) throws IOException {
        mOut.writeBytes(s);
    }

    @Override
    public void writeChars(String s) throws IOException {
        mOut.writeChars(s);
    }

    @Override
    public void writeUTF(String s) throws IOException {
        mOut.writeUTF(s);
    }

    @Override
    public void writeObject(Object obj) throws IOException {
        mOut.writeObject(obj);
    }

    @Override
    public void flush() throws IOException {
        mOut.flush();
    }

    @Override
    public void close() throws IOException {
        mOut.close();
    }
}
