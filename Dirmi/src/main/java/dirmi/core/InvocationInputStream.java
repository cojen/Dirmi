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
import java.io.InterruptedIOException;
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
 * @see InvocationOutputStream
 */
public class InvocationInputStream extends InputStream implements InvocationInput {
    private final InvocationChannel mChannel;
    private final ObjectInputStream mIn;

    /**
     * @param in stream to wrap
     */
    public InvocationInputStream(InvocationChannel channel, ObjectInputStream in) {
        mChannel = channel;
        mIn = in;
    }

    public void readFully(byte b[]) throws IOException {
        mIn.readFully(b);
    }

    public void readFully(byte b[], int offset, int length) throws IOException {
        mIn.readFully(b, offset, length);
    }

    public int skipBytes(int n) throws IOException {
        return mIn.skipBytes(n);
    }

    public boolean readBoolean() throws IOException {
        return mIn.readBoolean();
    }

    public byte readByte() throws IOException {
        return mIn.readByte();
    }

    public int readUnsignedByte() throws IOException {
        return mIn.readUnsignedByte();
    }

    public short readShort() throws IOException {
        return mIn.readShort();
    }

    public int readUnsignedShort() throws IOException {
        return mIn.readUnsignedShort();
    }

    public char readChar() throws IOException {
        return mIn.readChar();
    }

    public int readInt() throws IOException {
        return mIn.readInt();
    }

    public long readLong() throws IOException {
        return mIn.readLong();
    }

    public float readFloat() throws IOException {
        return mIn.readFloat();
    }

    public double readDouble() throws IOException {
        return mIn.readDouble();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public String readLine() {
        throw new UnsupportedOperationException();
    }

    public String readUTF() throws IOException {
        return mIn.readUTF();
    }

    public String readUnsharedString() throws IOException {
        int length = readVarUnsignedInteger();
        if (length == 0) {
            return null;
        }

        if (--length == 0) {
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
        return mIn.readUnshared();
    }

    public Object readObject() throws IOException, ClassNotFoundException {
        return mIn.readObject();
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

    private int readVarUnsignedInteger() throws IOException {
        InputStream in = mIn;
        int b1 = in.read();
        if (b1 < 0) {
            throw new EOFException();
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

    public Throwable readThrowable() throws IOException {
        int b = read();
        if (b < 0) {
            throw new EOFException();
        }
        if (b == InvocationOutputStream.NULL) {
            return null;
        }

        List<ThrowableInfo> chain = null;
        String serverLocalAddress = null;
        String serverRemoteAddress = null;
        Throwable t;
        try {
            ObjectInput in = mIn;
            serverLocalAddress = (String) in.readObject();
            serverRemoteAddress = (String) in.readObject();

            int chainLength = readVarUnsignedInteger();
            // Element zero is root cause.
            chain = new ArrayList<ThrowableInfo>(chainLength);

            for (int i=0; i<chainLength; i++) {
                chain.add(new ThrowableInfo(in));
            }

            t = (Throwable) in.readObject();
        } catch (IOException e) {
            if ((t = tryReconstruct(chain)) == null) {
                throw e;
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
        StackTraceElement[] mid;
        {
            List<StackTraceElement> elements = new ArrayList<StackTraceElement>(4);

            String message = "--- remote method invocation ---";
            addAddress(elements, message, "address",
                       serverLocalAddress, remoteAddress(mChannel));
            addAddress(elements, message, "address",
                       serverRemoteAddress, localAddress(mChannel));

            mid = elements.toArray(new StackTraceElement[elements.size()]);
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

        return t;
    }

    private static void addAddress(List<StackTraceElement> list, String message, String addrType,
                                   String addr1, String addr2)
    {
        if (addr1 == null || (addr2 != null && addr2.contains(addr1))) {
            list.add(new StackTraceElement(message, addrType, addr2, -1));
        } else if (addr2 == null || (addr1 != null && addr1.contains(addr2))) {
            list.add(new StackTraceElement(message, addrType, addr1, -1));
        } else {
            list.add(new StackTraceElement(message, addrType, addr1, -1));
            list.add(new StackTraceElement(message, addrType, addr2, -1));
        }
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

    @Override
    public String toString() {
        if (mChannel == null) {
            return super.toString();
        }
        return "InputStream for ".concat(mChannel.toString());
    }

    public void close() throws IOException {
        if (mChannel == null) {
            mIn.close();
        } else {
            mChannel.close();
        }
    }

    void doClose() throws IOException {
        mIn.close();
    }

    static String localAddress(InvocationChannel channel) {
        return channel == null ? null : toString(channel.getLocalAddress());
    }

    static String remoteAddress(InvocationChannel channel) {
        return channel == null ? null : toString(channel.getRemoteAddress());
    }

    static String toString(Object obj) {
        return obj == null ? null : obj.toString();
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
