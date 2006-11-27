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

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.InputStream;
import java.io.StreamCorruptedException;
import java.io.UTFDataFormatException;

import java.util.ArrayList;
import java.util.List;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see RemoteOutputStream
 */
public class RemoteInputStream extends InputStream implements RemoteInput {
    private volatile InputStream mIn;
    private final String mRemoteAddress;

    /**
     * @param in stream to wrap
     */
    public RemoteInputStream(InputStream in) {
        mIn = in;
        mRemoteAddress = null;
    }

    /**
     * @param in stream to wrap
     * @param remoteAddress optional remote address to stitch into stack traces from server.
     */
    public RemoteInputStream(InputStream in, String remoteAddress) {
        mIn = in;
        mRemoteAddress = remoteAddress;
    }

    public void readFully(byte b[]) throws IOException {
        readFully(b, 0, b.length);
    }

    public void readFully(byte b[], int offset, int length) throws IOException {
        if (length < 0) {
            throw new IndexOutOfBoundsException();
        }
        int n = 0;
        InputStream in = mIn;
        while (n < length) {
            int count = in.read(b, offset + n, length - n);
            if (count < 0) {
                throw new EOFException();
            }
            n += count;
        }
    }

    public int skipBytes(int n) throws IOException {
        int total = 0;
        int cur = 0;
        InputStream in = mIn;
        while ((total < n) && ((cur = (int) in.skip(n - total)) > 0)) {
            total += cur;
        }
        return total;
    }

    public boolean readBoolean() throws IOException {
        int b = mIn.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b != RemoteOutputStream.FALSE;
    }

    public byte readByte() throws IOException {
        int b = mIn.read();
        if (b < 0) {
            throw new EOFException();
        }
        return (byte) b;
    }

    public int readUnsignedByte() throws IOException {
        int b = mIn.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    public short readShort() throws IOException {
        InputStream in = mIn;
        int b1 = in.read();
        int b2 = in.read();
        if ((b1 | b2) < 0) {
            throw new EOFException();
        }
        return (short) ((b1 << 8) | b2);
    }

    public int readUnsignedShort() throws IOException {
        InputStream in = mIn;
        int b1 = in.read();
        int b2 = in.read();
        if ((b1 | b2) < 0) {
            throw new EOFException();
        }
        return (b1 << 8) | b2;
    }

    public char readChar() throws IOException {
        InputStream in = mIn;
        int b1 = in.read();
        int b2 = in.read();
        if ((b1 | b2) < 0) {
            throw new EOFException();
        }
        return (char) ((b1 << 8) | b2);
    }

    public int readInt() throws IOException {
        InputStream in = mIn;
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) {
            throw new EOFException();
        }
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
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

    /**
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public String readLine() {
        throw new UnsupportedOperationException();
    }

    public String readUTF() throws IOException {
        int utflen = readUnsignedShort();
        byte[] bytearr = new byte[utflen];
        char[] chararr = new char[utflen];

        int c, char2, char3;
        int count = 0;
        int chararr_count = 0;

        readFully(bytearr, 0, utflen);

        while (count < utflen) {
            c = (int) bytearr[count] & 0xff;      
            if (c > 127) {
                break;
            }
            count++;
            chararr[chararr_count++] = (char) c;
        }

        while (count < utflen) {
            c = (int) bytearr[count] & 0xff;
            switch (c >> 4) {
            case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
                /* 0xxxxxxx*/
                count++;
                chararr[chararr_count++] = (char) c;
                break;
            case 12: case 13:
                /* 110x xxxx   10xx xxxx*/
                count += 2;
                if (count > utflen) {
                    throw new UTFDataFormatException("malformed input: partial character at end");
                }
                char2 = (int) bytearr[count-1];
                if ((char2 & 0xC0) != 0x80) {
                    throw new UTFDataFormatException("malformed input around byte " + count); 
                }
                chararr[chararr_count++] = (char) (((c & 0x1F) << 6) | (char2 & 0x3F));  
                break;
            case 14:
                /* 1110 xxxx  10xx xxxx  10xx xxxx */
                count += 3;
                if (count > utflen) {
                    throw new UTFDataFormatException("malformed input: partial character at end");
                }
                char2 = (int) bytearr[count-2];
                char3 = (int) bytearr[count-1];
                if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80)) {
                    throw new UTFDataFormatException("malformed input around byte " + (count - 1));
                }
                chararr[chararr_count++] = (char)(((c     & 0x0F) << 12) |
                                                  ((char2 & 0x3F) << 6)  |
                                                  ((char3 & 0x3F) << 0));
                break;
            default:
                /* 10xx xxxx,  1111 xxxx */
                throw new UTFDataFormatException("malformed input around byte " + count);
            }
        }

        return new String(chararr, 0, chararr_count);
    }

    public String readUnsharedString() throws IOException {
        Integer lengthObj = readVarUnsignedInteger();
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
            int b1 = in.read();
            switch (b1 >> 5) {
            case 0: case 1: case 2: case 3:
                // 0xxxxxxx
                value[offset++] = (char) b1;
                break;
            case 4: case 5:
                // 10xxxxxx xxxxxxxx
                int b2 = in.read();
                if (b2 < 0) {
                    throw new EOFException();
                }
                value[offset++] = (char) (((b1 & 0x3f) << 8) | b2);
                break;
            case 6:
                // 110xxxxx xxxxxxxx xxxxxxxx
                b2 = in.read();
                int b3 = in.read();
                if ((b2 | b3) < 0) {
                    throw new EOFException();
                }
                int c = ((b1 & 0x1f) << 16) | (b2 << 8) | b3;
                if (c >= 0x10000) {
                    // Split into surrogate pair.
                    c -= 0x10000;
                    value[offset++] = (char) (0xd800 | ((c >> 10) & 0x3ff));
                    value[offset++] = (char) (0xdc00 | (c & 0x3ff));
                } else {
                    value[offset++] = (char) c;
                }
                break;
            default:
                // 111xxxxx
                // Illegal.
                if (b1 < 0) {
                    throw new EOFException();
                } else {
                    throw new StreamCorruptedException();
                }
            }
        }

        return new String(value);
    }

    public Object readUnshared() throws IOException, ClassNotFoundException {
        return getObjectInputStream().readUnshared();
    }

    public Object readObject() throws IOException, ClassNotFoundException {
        return getObjectInputStream().readObject();
    }

    public int read() throws IOException {
        return mIn.read();
    }

    public int read(byte[] b) throws IOException {
        return mIn.read(b);
    }

    public int read(byte[] b, int offset, int length) throws IOException {
        return mIn.read(b, offset, length);
    }

    public long skip(long n) throws IOException {
        return mIn.skip(n);
    }

    public int available() throws IOException {
        return mIn.available();
    }

    private ObjectInputStream getObjectInputStream() throws IOException {
        if (!(mIn instanceof ObjectInputStream)) {
            mIn = createObjectInputStream(mIn);
        }
        return (ObjectInputStream) mIn;
    }

    private Integer readVarUnsignedInteger() throws IOException {
        InputStream in = mIn;
        int b1 = in.read();
        if (b1 < 0) {
            throw new EOFException();
        }
        if (b1 == RemoteOutputStream.NULL) {
            return null;
        }
        if (b1 <= 0x7f) {
            return b1;
        }
        int b2 = in.read();
        if (b2 < 0) {
            throw new EOFException();
        }
        if (b1 <= 0xbf) {
            return ((b1 & 0x3f) << 8) | b2;
        }
        int b3 = in.read();
        if (b3 < 0) {
            throw new EOFException();
        }
        if (b1 <= 0xdf) {
            return ((b1 & 0x1f) << 16) | (b2 << 8) | b3;
        }
        int b4 = in.read();
        if (b4 < 0) {
            throw new EOFException();
        }
        if (b1 <= 0xef) {
            return ((b1 & 0x0f) << 24) | (b2 << 16) | (b3 << 8) | b4;
        }
        int b5 = in.read();
        if (b5 < 0) {
            throw new EOFException();
        }
        return (b2 << 24) | (b3 << 16) | (b4 << 8) | b5;
    }

    public boolean readOk() throws RemoteException, Throwable {
        List<ThrowableInfo> chain = null;
        String serverLocalAddress = null;
        Throwable t;
        try {
            int v = mIn.read();
            if (v != RemoteOutputStream.NOT_OK) {
                return v == RemoteOutputStream.OK_TRUE;
            }

            ObjectInput in = getObjectInputStream();
            serverLocalAddress = (String) in.readObject();

            int chainLength = readVarUnsignedInteger();
            // Element zero is root cause.
            chain = new ArrayList<ThrowableInfo>(chainLength);

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

        // Add some fake traces in the middle which clearly indicate that a
        // method was invoked remotely. Also include any addresses.
        StackTraceElement[] mid = null;
        if (mRemoteAddress == null && serverLocalAddress == null) {
            mid = new StackTraceElement[] {
                new StackTraceElement("Remote Method Invocation", "", null, -1)
            };
        } else {
            mid = new StackTraceElement[] {
                new StackTraceElement
                ("Remote Method Invocation", "server", serverLocalAddress, -1),
                new StackTraceElement
                ("Remote Method Invocation", "client", mRemoteAddress, -1)
            };
        }

        if (localTraceLength >= 1) {
            StackTraceElement[] combined;
            combined = new StackTraceElement[remoteTrace.length + mid.length + localTraceLength];
            System.arraycopy(remoteTrace, 0, combined, 0, remoteTrace.length);
            System.arraycopy(mid, 0, combined, remoteTrace.length, mid.length);
            System.arraycopy(localTrace, localTraceOffest,
                             combined, remoteTrace.length + mid.length, localTraceLength);
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

    /**
     * Override this method to return a subclassed ObjectInputStream.
     */
    protected ObjectInputStream createObjectInputStream(InputStream in) throws IOException {
        return new ObjectInputStream(in);
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
