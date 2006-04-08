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

package dirmi.io;

import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteOutputStream implements RemoteOutput {
    static final byte NULL = -1;
    static final byte NOT_NULL = 1;
    static final byte FALSE = 2;
    static final byte TRUE = 3;
    static final byte OK_FALSE = 4;
    static final byte OK_TRUE = 5;
    static final byte NOT_OK = 6;

    private volatile OutputStream mOut;

    public RemoteOutputStream(OutputStream out) {
        mOut = out;
    }

    public void write(boolean v) throws IOException {
        mOut.write(v ? TRUE : FALSE);
    }

    public void write(byte v) throws IOException {
        mOut.write(v);
    }

    public void write(short v) throws IOException {
        OutputStream out = mOut;
        out.write(v >> 8);
        out.write(v);
    }

    public void write(char v) throws IOException {
        OutputStream out = mOut;
        out.write(v >> 8);
        out.write(v);
    }

    public void write(int v) throws IOException {
        OutputStream out = mOut;
        out.write(v >> 24);
        out.write(v >> 16);
        out.write(v >> 8);
        out.write(v);
    }

    public void write(long v) throws IOException {
        write((int) v >> 32);
        write((int) v);
    }

    public void write(float v) throws IOException {
        write(Float.floatToRawIntBits(v));
    }

    public void write(double v) throws IOException {
        write(Double.doubleToRawLongBits(v));
    }

    public void write(Boolean v) throws IOException {
        mOut.write(v == null ? NULL : (v ? TRUE : FALSE));
    }

    // TODO: Remove custom object writing methods. Annotations can control
    // custom object writing instead.

    public void write(Byte v) throws IOException {
        OutputStream out = mOut;
        if (v == null) {
            out.write(NULL);
        } else {
            out.write(NOT_NULL);
            out.write(v.intValue());
        }
    }

    public void write(Short v) throws IOException {
        if (v == null) {
            mOut.write(NULL);
        } else {
            mOut.write(NOT_NULL);
            write(v.shortValue());
        }
    }

    public void write(Character v) throws IOException {
        if (v == null) {
            mOut.write(NULL);
        } else {
            mOut.write(NOT_NULL);
            write(v.charValue());
        }
    }

    public void write(Integer v) throws IOException {
        if (v == null) {
            mOut.write(NULL);
        } else {
            mOut.write(NOT_NULL);
            write(v.intValue());
        }
    }

    public void write(Long v) throws IOException {
        if (v == null) {
            mOut.write(NULL);
        } else {
            mOut.write(NOT_NULL);
            write(v.longValue());
        }
    }

    public void write(Float v) throws IOException {
        if (v == null) {
            mOut.write(NULL);
        } else {
            mOut.write(NOT_NULL);
            write(v.floatValue());
        }
    }

    public void write(Double v) throws IOException {
        if (v == null) {
            mOut.write(NULL);
        } else {
            mOut.write(NOT_NULL);
            write(v.doubleValue());
        }
    }

    public void write(String str) throws IOException {
        if (str == null) {
            mOut.write(NULL);
        }

        int length = str.length();

        writeLength(length);

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

    public void write(Object obj) throws IOException {
        getObjectOutput().writeObject(obj);
    }

    private ObjectOutput getObjectOutput() throws IOException {
        if (!(mOut instanceof ObjectOutput)) {
            mOut = new ObjectOutputStream(mOut);
        }
        return (ObjectOutput) mOut;
    }

    public void writeLength(int length) throws IOException {
        OutputStream out = mOut;
        if (length < 128) {
            out.write(length);
        } else if (length < 16384) {
            out.write((length >> 8) | 0x80);
            out.write(length);
        } else if (length < 2097152) {
            out.write((length >> 16) | 0xc0);
            out.write(length >> 8);
            out.write(length);
        } else if (length < 268435456) {
            out.write((length >> 24) | 0xe0);
            out.write(length >> 16);
            out.write(length >> 8);
            out.write(length);
        } else {
            out.write(0xf0);
            out.write(length >> 24);
            out.write(length >> 16);
            out.write(length >> 8);
            out.write(length);
        }
    }

    public void writeNull() throws IOException {
        mOut.write(NULL);
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

        writeLength(chain.size());

        ObjectOutput out = getObjectOutput();

        for (int i=0; i<chain.size(); i++) {
            Throwable sub = chain.get(i);
            out.writeObject(sub.getClass().getName());
            out.writeObject(sub.getMessage());
            out.writeObject(sub.getStackTrace());
        }

        // Ensure caller gets something before we try to serialize the whole Throwable.
        out.flush();

        // Write the Throwable in all its glory.
        out.writeObject(t);
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
}
