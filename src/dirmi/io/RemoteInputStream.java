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
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.InputStream;
import java.io.StreamCorruptedException;

import java.util.ArrayList;
import java.util.List;

import java.rmi.RemoteException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteInputStream implements RemoteInput {
    private volatile InputStream mIn;

    public RemoteInputStream(InputStream in) {
        mIn = in;
    }

    public boolean readBoolean() throws IOException {
        return mIn.read() == RemoteOutputStream.TRUE;
    }

    public byte readByte() throws IOException {
        return (byte) mIn.read();
    }

    public short readShort() throws IOException {
        InputStream in = mIn;
        return (short) ((in.read() << 8) | in.read());
    }

    public char readChar() throws IOException {
        InputStream in = mIn;
        return (char) ((in.read() << 8) | in.read());
    }

    public int readInt() throws IOException {
        InputStream in = mIn;
        return (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
    }

    public long readLong() throws IOException {
        return (((long) readInt()) << 32) | (readInt() & 0xffffffffL);
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    public boolean readBooleanObj() throws IOException {
        int v = mIn.read();
        return v == RemoteOutputStream.NULL ? null : (v == RemoteOutputStream.TRUE);
    }

    public Byte readByteObj() throws IOException {
        InputStream in = mIn;
        int v = in.read();
        if (v == RemoteOutputStream.NULL) {
            return null;
        }
        return (byte) in.read();
    }

    public Short readShortObj() throws IOException {
        if (mIn.read() == RemoteOutputStream.NULL) {
            return null;
        }
        return readShort();
    }

    public Character readCharacterObj() throws IOException {
        if (mIn.read() == RemoteOutputStream.NULL) {
            return null;
        }
        return readChar();
    }

    public Integer readIntegerObj() throws IOException {
        if (mIn.read() == RemoteOutputStream.NULL) {
            return null;
        }
        return readInt();
    }

    public Long readLongObj() throws IOException {
        if (mIn.read() == RemoteOutputStream.NULL) {
            return null;
        }
        return readLong();
    }

    public Float readFloatObj() throws IOException {
        if (mIn.read() == RemoteOutputStream.NULL) {
            return null;
        }
        return readFloat();
    }

    public Double readDoubleObj() throws IOException {
        if (mIn.read() == RemoteOutputStream.NULL) {
            return null;
        }
        return readDouble();
    }

    public String readString() throws IOException {
        Integer lengthObj = readLength();
        if (lengthObj == null) {
            return null;
        }

        int length = lengthObj;
        if (length == 0) {
            return "";
        }

        char[] value = new char[length];
        int offset = 0;

        InputStream in = mIn;
        while (offset < length) {
            int c = mIn.read();
            switch (c >> 5) {
            case 0: case 1: case 2: case 3:
                // 0xxxxxxx
                value[offset++] = (char)c;
                break;
            case 4: case 5:
                // 10xxxxxx xxxxxxxx
                value[offset++] = (char)(((c & 0x3f) << 8) | in.read());
                break;
            case 6:
                // 110xxxxx xxxxxxxx xxxxxxxx
                c = ((c & 0x1f) << 16) | (in.read() << 8) | in.read();
                if (c >= 0x10000) {
                    // Split into surrogate pair.
                    c -= 0x10000;
                    value[offset++] = (char)(0xd800 | ((c >> 10) & 0x3ff));
                    value[offset++] = (char)(0xdc00 | (c & 0x3ff));
                } else {
                    value[offset++] = (char)c;
                }
                break;
            default:
                // 111xxxxx
                // Illegal.
                throw new StreamCorruptedException();
            }
        }

        return new String(value);
    }

    public Object readObject() throws IOException, ClassNotFoundException {
        return getObjectInput().readObject();
    }

    private ObjectInput getObjectInput() throws IOException {
        if (!(mIn instanceof ObjectInput)) {
            mIn = new ObjectInputStream(mIn);
        }
        return (ObjectInput) mIn;
    }

    public Integer readLength() throws IOException {
        InputStream in = mIn;
        int v = in.read();
        if (v >= 0xf8) {
            return null;
        } else if (v <= 0x7f) {
            return v;
        } else if (v <= 0xbf) {
            return ((v & 0x3f) << 8) | in.read();
        } else if (v <= 0xdf) {
            return ((v & 0x1f) << 16) | (in.read() << 8) | in.read();
        } else if (v <= 0xef) {
            return ((v & 0x0f) << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
        } else {
            return (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
        }
    }

    public boolean readOk() throws RemoteException, Throwable {
        List<ThrowableInfo> chain = null;
        Throwable t;
        try {
            int v = mIn.read();
            if (v != RemoteOutputStream.NOT_OK) {
                return v == RemoteOutputStream.OK_TRUE;
            }

            int chainLength = readLength();
            // Element zero is root cause.
            chain = new ArrayList<ThrowableInfo>(chainLength);

            ObjectInput in = getObjectInput();

            for (int i=0; i<chainLength; i++) {
                chain.add(new ThrowableInfo(in));
            }

            t = (Throwable) in.readObject();
        } catch (IOException e) {
            if ((t = tryReconstruct(chain)) == null) {
                throw new RemoteException(e.getMessage(), e);
            }
        } catch (ClassNotFoundException e) {
            if ((t = tryReconstruct(chain)) == null) {
                throw new RemoteException(e.getMessage(), e);
            }
        }

        // Now stitch local stack trace after remote trace.

        StackTraceElement[] remoteTrace = t.getStackTrace();

        StackTraceElement[] localTrace;
        try {
            localTrace = Thread.currentThread().getStackTrace();
        } catch (SecurityException e) {
            // Fine, use this trace instead.
            localTrace = e.getStackTrace();
        }

        int localTraceLength = localTrace.length;
        int localTraceOffest = 0;
        if (localTraceLength >= 2) {
            // Exclude most recent method [0] from local trace, as it provides
            // little extra context.
            localTraceLength--;
            localTraceOffest++;
        }
        if (localTraceLength >= 1) {
            StackTraceElement[] combined;
            combined = new StackTraceElement[remoteTrace.length + localTraceLength];
            System.arraycopy(remoteTrace, 0, combined, 0, remoteTrace.length);
            System.arraycopy(localTrace, localTraceOffest,
                             combined, remoteTrace.length, localTraceLength);
            t.setStackTrace(combined);
        }

        throw t;
    }

    private RemoteException tryReconstruct(List<ThrowableInfo> chain) {
        if (chain == null || chain.size() == 0) {
            return null;
        }

        // Reconstruct exception by chaining together RemoteExceptions.

        RemoteException cause = null;
        for (ThrowableInfo info : chain) {
            if (info != null) {
                String message = info.mClassName + ": " + info.mMessage;
                if (cause == null) {
                    cause = new RemoteException(message);
                } else {
                    cause = new RemoteException(message, cause);
                }
                cause.setStackTrace(info.mStackTrace);
            }
        }

        return cause;
    }

    public void close() throws IOException {
        mIn.close();
    }

    private static class ThrowableInfo {
        final String mClassName;
        final String mMessage;
        final StackTraceElement[] mStackTrace;

        ThrowableInfo(ObjectInput in) throws IOException, ClassNotFoundException {
            mClassName = (String) in.readObject();
            mMessage = (String) in.readObject();
            mStackTrace = (StackTraceElement[]) in.readObject();
        }
    }
}
