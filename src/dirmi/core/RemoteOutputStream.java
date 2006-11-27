/*
 *  Copyright 2006 Brian S O'Neill
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

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see RemoteInputStream
 */
public class RemoteOutputStream extends OutputStream implements RemoteOutput {
    static final byte FALSE = 0;
    static final byte TRUE = 1;
    static final byte OK_FALSE = 2;
    static final byte OK_TRUE = 3;
    static final byte NOT_OK = 4;
    static final byte NULL = 5;
    static final byte NOT_NULL = 6;

    private volatile OutputStream mOut;
    private final String mLocalAddress;

    /**
     * @param out stream to wrap
     */
    public RemoteOutputStream(OutputStream out) {
        mOut = out;
        mLocalAddress = null;
    }

    /**
     * @param out stream to wrap
     * @param localAddress optional local address to stitch into stack traces sent to client.
     */
    public RemoteOutputStream(OutputStream out, String localAddress) {
        mOut = out;
        mLocalAddress = localAddress;
    }

    public void write(int b) throws IOException {
        mOut.write(b);
    }

    public void write(byte[] b) throws IOException {
        mOut.write(b);
    }

    public void write(byte[] b, int offset, int length) throws IOException {
        mOut.write(b, offset, length);
    }

    public void writeBoolean(boolean v) throws IOException {
        mOut.write(v ? TRUE : FALSE);
    }

    public void writeByte(int v) throws IOException {
        mOut.write(v);
    }

    public void writeShort(int v) throws IOException {
        OutputStream out = mOut;
        out.write(v >> 8);
        out.write(v);
    }

    public void writeChar(int v) throws IOException {
        OutputStream out = mOut;
        out.write(v >> 8);
        out.write(v);
    }

    public void writeInt(int v) throws IOException {
        OutputStream out = mOut;
        out.write(v >> 24);
        out.write(v >> 16);
        out.write(v >> 8);
        out.write(v);
    }

    public void writeLong(long v) throws IOException {
        writeInt((int) (v >> 32));
        writeInt((int) v);
    }

    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToRawIntBits(v));
    }

    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToRawLongBits(v));
    }

    public void writeBytes(String s) throws IOException {
        int length = s.length();
        OutputStream out = mOut;
        for (int i=0; i<length; i++) {
            out.write(s.charAt(i));
        }
    }

    public void writeChars(String s) throws IOException {
        int length = s.length();
        OutputStream out = mOut;
        for (int i=0; i<length; i++) {
            int c = s.charAt(i);
            out.write(c >> 8);
            out.write(c);
        }
    }

    public void writeUTF(String s) throws IOException {
        int length = s.length();

        int utflen = 0;
        for (int i=0; i<length; i++) {
            int c = s.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007F)) {
                utflen++;
            } else if (c > 0x07FF) {
                utflen += 3;
            } else {
                utflen += 2;
            }
        }

        if (utflen > 65535) {
            throw new UTFDataFormatException("encoded string too long: " + utflen + " bytes");
        }

        OutputStream out = mOut;

        out.write(utflen >> 8);
        out.write(utflen);

        for (int i=0; i<length; i++) {
            int c = s.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007f)) {
                out.write(c);
            } else if (c > 0x07ff) {
                out.write(0xe0 | ((c >> 12) & 0x0f));
                out.write(0x80 | ((c >> 6) & 0x3f));
                out.write(0x80 | (c & 0x3f));
            } else {
                out.write(0xc0 | ((c >> 6) & 0x1f));
                out.write(0x80 | (c & 0x3f));
            }
        }
    }

    /**
     * @param str string of any length or null
     */
    public void writeUnsharedString(String str) throws IOException {
        if (str == null) {
            mOut.write(NULL);
        }

        int length = str.length();

        writeVarUnsignedInt(length);

        // Strings are encoded in a fashion similar to UTF-8, in that ASCII
        // characters are written in one byte. This encoding is more efficient
        // than UTF-8, but it isn't compatible with UTF-8.
 
        OutputStream out = mOut;
        for (int i = 0; i < length; i++) {
            int c = str.charAt(i);
            if (c <= 0x7f) {
                out.write(c);
            } else if (c <= 0x3fff) {
                out.write(0x80 | (c >> 8));
                out.write(c);
            } else {
                if (c >= 0xd800 && c <= 0xdbff) {
                    // Found a high surrogate. Verify that surrogate pair is
                    // well-formed. Low surrogate must follow high surrogate.
                    if (i + 1 < length) {
                        int c2 = str.charAt(i + 1);
                        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
                            c = 0x10000 + (((c & 0x3ff) << 10) | (c2 & 0x3ff));
                            i++;
                        }
                    }
                }
                out.write(0xc0 | (c >> 16));
                out.write(c >> 8);
                out.write(c);
            }
        }
    }

    private void writeVarUnsignedInt(int v) throws IOException {
        OutputStream out = mOut;
        if (v < (1 << 7)) {
            out.write(v);
        } else if (v < (1 << 14)) {
            out.write((v >> 8) | 0x80);
            out.write(v);
        } else if (v < (1 << 21)) {
            out.write((v >> 16) | 0xc0);
            out.write(v >> 8);
            out.write(v);
        } else if (v < (1 << 28)) {
            out.write((v >> 24) | 0xe0);
            out.write(v >> 16);
            out.write(v >> 8);
            out.write(v);
        } else {
            out.write(0xf0);
            out.write(v >> 24);
            out.write(v >> 16);
            out.write(v >> 8);
            out.write(v);
        }
    }

    public void writeUnshared(Object obj) throws IOException {
        getObjectOutputStream().writeUnshared(obj);
    }

    public void writeObject(Object obj) throws IOException {
        getObjectOutputStream().writeObject(obj);
    }

    private ObjectOutputStream getObjectOutputStream() throws IOException {
        if (!(mOut instanceof ObjectOutputStream)) {
            mOut = createObjectOutputStream(mOut);
        }
        return (ObjectOutputStream) mOut;
    }

    public void writeOk() throws IOException {
        // Caller should not care if true or false.
        mOut.write(OK_TRUE);
    }

    public void writeOk(boolean result) throws IOException {
        mOut.write(result ? OK_TRUE : OK_FALSE);
    }

    public void writeThrowable(Throwable t) throws IOException {
        write(NOT_OK);

        // Could just serialize Throwable, however:
        // 1. Caller might not have class for Throwable
        // 2. Throwable might not actually be serializable

        // So write as much as possible without having to serialize actual
        // Throwable, and then write serialized Throwable. If a
        // NotSerializableException is thrown, at least caller got some info.

        List<Throwable> chain = new ArrayList<Throwable>(8);
        // Element zero is root cause.
        collectChain(chain, t);

        ObjectOutput out = getObjectOutputStream();
        out.writeObject(mLocalAddress);

        writeVarUnsignedInt(chain.size());

        for (int i=0; i<chain.size(); i++) {
            Throwable sub = chain.get(i);
            out.writeObject(sub.getClass().getName());
            out.writeObject(sub.getMessage());
            out.writeObject(sub.getStackTrace());
        }

        // Ensure caller gets something before we try to serialize the whole Throwable.
        out.flush();

        try {
            // Write the Throwable in all its glory.
            out.writeObject(t);
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                // Don't care.
            }
        }
    }

    private void collectChain(List<Throwable> chain, Throwable t) {
        Throwable cause = t.getCause();
        if (cause != null) {
            collectChain(chain, cause);
        }
        chain.add(t);
    }

    public void flush() throws IOException {
        mOut.flush();
    }

    public void close() throws IOException {
        mOut.close();
    }

    /**
     * Override this method to return a subclassed ObjectOutputStream.
     */
    protected ObjectOutputStream createObjectOutputStream(OutputStream out) throws IOException {
        return new ObjectOutputStream(out);
    }

}
