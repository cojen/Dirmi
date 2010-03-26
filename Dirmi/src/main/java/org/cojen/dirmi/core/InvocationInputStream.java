/*
 *  Copyright 2006-2010 Brian S O'Neill
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

import java.lang.reflect.Constructor;

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

import org.cojen.dirmi.ReconstructedException;

/**
 * Standard implementation of {@link InvocationInput}.
 *
 * @author Brian S O'Neill
 * @see InvocationOutputStream
 */
public class InvocationInputStream extends InputStream implements InvocationInput {
    private static final String STUB_GENERATOR_NAME;

    static {
        STUB_GENERATOR_NAME = StubFactoryGenerator.class.getName();
    }

    private final InvocationChannel mChannel;
    private final ObjectInputStream mIn;

    /**
     * @param in stream to wrap
     */
    public InvocationInputStream(InvocationChannel channel, ObjectInputStream in) {
        mChannel = channel;
        mIn = in;
    }

    public void readFully(byte[] b) throws IOException {
        mIn.readFully(b);
    }

    public void readFully(byte[] b, int offset, int length) throws IOException {
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

    public Throwable readThrowable() throws IOException, ReconstructedException {
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
        Throwable reconstructCause;
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
            reconstructCause = null;
        } catch (IOException e) {
            if ((t = tryReconstruct(e, chain)) == null) {
                throw e;
            }
            reconstructCause = e;
        } catch (ClassNotFoundException e) {
            if ((t = tryReconstruct(e, chain)) == null) {
                throw new RemoteException(e.getMessage(), e);
            }
            reconstructCause = e;
        }

        // Stitch local stack trace after each remote trace.
        {
            StackTraceElement[] localTrace = localTrace(serverLocalAddress, serverRemoteAddress);

            StackTraceElement[] rootRemoteTrace = t.getStackTrace();
            int skeletonOffset = -1;
            {
                for (int i=0; i<rootRemoteTrace.length; i++) {
                    if (InvocationOutputStream.SKELETON_GENERATOR_NAME
                        .equals(rootRemoteTrace[i].getFileName()))
                    {
                        skeletonOffset = i;
                        break;
                    }
                }
            }

            t.setStackTrace(stitchTrace(rootRemoteTrace, localTrace));

            Throwable cause = t;
            outer: while ((cause = cause.getCause()) != null) {
                // Stitch local trace only if matches through to skeleton offset.
                StackTraceElement[] causeTrace = cause.getStackTrace();
                for (int i = rootRemoteTrace.length, j = causeTrace.length;
                     --i >= skeletonOffset && --j >= 0 ;)
                {
                    if (!rootRemoteTrace[i].equals(causeTrace[j])) {
                        continue outer;
                    }
                }

                cause.setStackTrace(stitchTrace(causeTrace, localTrace));
            }
        }

        if (reconstructCause == null) {
            return t;
        }

        if (t instanceof ReconstructedException) {
            throw (ReconstructedException) t;
        }

        throw new ReconstructedException(reconstructCause, t);
    }

    private StackTraceElement[] stitchTrace(StackTraceElement[] remoteTrace,
                                            StackTraceElement[] localTrace)
    {
        int remoteTraceLength = remoteTrace.length;

        if (remoteTraceLength > 1 &&
            InvocationOutputStream.SKELETON_GENERATOR_NAME
            .equals(remoteTrace[remoteTraceLength - 1].getFileName()))
        {
            // Prune off skeleton method, which was used as marker for
            // determining if traces should be stitched.
            remoteTraceLength--;
        }

        StackTraceElement[] combined = new StackTraceElement
            [remoteTraceLength + localTrace.length];
        System.arraycopy(remoteTrace, 0, combined, 0, remoteTraceLength);
        System.arraycopy(localTrace, 0, combined, remoteTraceLength, localTrace.length);
        return combined;
    }

    private StackTraceElement[] localTrace(String serverLocalAddress, String serverRemoteAddress) {
        List<StackTraceElement> trace = new ArrayList<StackTraceElement>();

        // Add some fake traces which clearly indicate that a method was
        // invoked remotely. Also include any addresses.
        String message = "--- remote method invocation ---";
        addAddress(trace, message, "address", serverLocalAddress, remoteAddress(mChannel));
        addAddress(trace, message, "address", serverRemoteAddress, localAddress(mChannel));

        StackTraceElement[] localTrace;
        try {
            localTrace = Thread.currentThread().getStackTrace();
        } catch (SecurityException e) {
            // Fine, use this trace instead.
            localTrace = e.getStackTrace();
        }

        // Ignore everything before the stub class.
        int i;
        for (i=0; i<localTrace.length; i++) {
            if (STUB_GENERATOR_NAME.equals(localTrace[i].getFileName())) {
                break;
            }
        }

        if (i >= localTrace.length) {
            // Couldn't find pattern for some reason, so keep everything.
            i = 0;
        }

        for (; i<localTrace.length; i++) {
            trace.add(localTrace[i]);
        }

        return trace.toArray(new StackTraceElement[trace.size()]);
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

    private Throwable tryReconstruct(Throwable reconstructCause, List<ThrowableInfo> chain) {
        if (chain == null || chain.size() == 0) {
            return null;
        }

        // Reconstruct exception by chaining together exceptions.

        Throwable cause = null;
        for (ThrowableInfo info : chain) {
            if (info != null) {
                cause = tryReconstruct(reconstructCause, info.mClassName, info.mMessage, cause);
                cause.setStackTrace(info.mStackTrace);
            }
        }

        return cause;
    }

    private Throwable tryReconstruct(Throwable reconstructCause,
                                     String className, String message, Throwable cause)
    {
        try {
            // FIXME: use session's class loader
            Class exClass = Class.forName(className);

            int[] preferences;
            if (cause == null) {
                if (message == null) {
                    preferences = new int[] {
                        CTOR_NO_ARG,
                        CTOR_MESSAGE,
                        CTOR_CAUSE,
                        CTOR_MESSAGE_AND_CAUSE,
                        CTOR_CAUSE_AND_MESSAGE,
                    };
                } else {
                    preferences = new int[] {
                        CTOR_MESSAGE,
                        CTOR_MESSAGE_AND_CAUSE,
                        CTOR_CAUSE_AND_MESSAGE,
                    };
                }
            } else if (message == null) {
                preferences = new int[] {
                    CTOR_CAUSE,
                    CTOR_CAUSE_AND_MESSAGE,
                    CTOR_MESSAGE_AND_CAUSE,
                    CTOR_NO_ARG,
                    CTOR_MESSAGE,
                };
            } else {
                preferences = new int[] {
                    CTOR_MESSAGE_AND_CAUSE,
                    CTOR_CAUSE_AND_MESSAGE,
                    CTOR_MESSAGE,
                };
            }

            Throwable t = tryReconstruct(exClass, message, cause, preferences);

            if (t != null) {
                return t;
            }
        } catch (Exception e) {
            // Ignore.
        }

        return new ReconstructedException(reconstructCause, className, message, cause);
    }

    private static final int
        CTOR_NO_ARG = 1,
        CTOR_MESSAGE = 2,
        CTOR_CAUSE = 3,
        CTOR_MESSAGE_AND_CAUSE = 4,
        CTOR_CAUSE_AND_MESSAGE = 5;

    private Throwable tryReconstruct(Class exClass, String message, Throwable cause,
                                     int... preferences)
        throws Exception
    {
        Throwable t = null;

        for (int pref : preferences) {
            for (Constructor ctor : exClass.getConstructors()) {
                Class[] paramTypes = ctor.getParameterTypes();

                switch (pref) {
                case CTOR_NO_ARG:
                    if (paramTypes.length == 0) {
                        t = (Throwable) ctor.newInstance();
                        break;
                    }
                    break;

                case CTOR_MESSAGE:
                    if (paramTypes.length == 1 &&
                        paramTypes[0] == String.class)
                    {
                        t = (Throwable) ctor.newInstance(message);
                        break;
                    }
                    break;

                case CTOR_CAUSE:
                    if (paramTypes.length == 1 &&
                        isAssignableThrowable(paramTypes[0], cause))
                    {
                        return (Throwable) ctor.newInstance(cause);
                    }
                    break;

                case CTOR_MESSAGE_AND_CAUSE:
                    if (paramTypes.length == 2 &&
                        paramTypes[0] == String.class &&
                        isAssignableThrowable(paramTypes[1], cause))
                    {
                        return (Throwable) ctor.newInstance(message, cause);
                    }
                    break;
 
                case CTOR_CAUSE_AND_MESSAGE:
                    if (paramTypes.length == 2 &&
                        isAssignableThrowable(paramTypes[0], cause) &&
                        paramTypes[1] == String.class)
                    {
                        return (Throwable) ctor.newInstance(cause, message);
                    }
                    break;
                }
            }
        }

        if (t != null && cause != null) {
            try {
                t.initCause(cause);
            } catch (IllegalStateException e) {
            }
        }

        return t;
    }

    private boolean isAssignableThrowable(Class type, Throwable cause) {
        return cause == null && Throwable.class.isAssignableFrom(type) || type.isInstance(cause);
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
