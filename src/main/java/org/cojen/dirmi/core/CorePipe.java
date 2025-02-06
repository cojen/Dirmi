/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import java.io.OutputStream;

import java.net.SocketAddress;

import org.cojen.dirmi.Pipe;

/**
 * Pipe implementation used by CoreSession.
 *
 * @author Brian S O'Neill
 */
final class CorePipe extends BufferedPipe {
    // Values for mMode.
    static final int M_CLIENT = 1, M_SERVER = 2, M_CLOSED = 3;

    // When true, calling recycle on a client-side pipe closes it instead. This feature is
    // intended to help diagnose issues caused by incorrect pipe recycling.
    private static final boolean RECYCLE_CLOSE;

    static {
        RECYCLE_CLOSE = Boolean.getBoolean(Pipe.class.getName() + ".RECYCLE_CLOSE");
    }

    /**
     * Returns a pipe which is connected to null I/O streams.
     */
    static CorePipe newNullPipe(SocketAddress localAddr, SocketAddress remoteAddr) {
        return new CorePipe(localAddr, remoteAddr,
                            InputStream.nullInputStream(), OutputStream.nullOutputStream(),
                            M_CLOSED);
    }

    // The following fields are accessed by CoreSession.

    CoreSession<?> mSession;

    CorePipe mConPrev, mConNext;

    int mClock;

    int mMode;

    CorePipe(SocketAddress localAddr, SocketAddress remoteAddr,
             InputStream in, OutputStream out, int mode)
    {
        super(localAddr, remoteAddr, in, out);
        mMode = mode;
    }

    @Override
    Object serverFor(long id) throws IOException {
        return mSession.serverFor(id);
    }

    @Override
    Object stubFor(long id, long typeId) throws IOException {
        return mSession.stubFor(id, typeId);
    }

    @Override
    Object stubFor(long id, long typeId, RemoteInfo info) {
        return mSession.stubFor(id, typeId, info);
    }

    @Override
    Class<?> loadClass(String name) throws ClassNotFoundException {
        return mSession.loadClass(name);
    }

    @Override
    void writeStub(Stub stub) throws IOException {
        refModeOffIfDisabled();
        requireOutput(9);
        int end = mOutEnd;
        byte[] buf = mOutBuffer;
        buf[end++] = TypeCodes.T_REMOTE;
        cLongArrayBEHandle.set(buf, end, stub.id);
        mOutEnd = end + 8;
    }

    @Override
    void writeSkeleton(Object server) throws IOException {
        mSession.writeSkeleton(this, server);
    }

    /**
     * @param typeCode T_REMOTE_T or T_REMOTE_TI
     */
    void writeSkeletonHeader(byte typeCode, Skeleton skeleton) throws IOException {
        refModeOffIfDisabled();
        requireOutput(17);
        int end = mOutEnd;
        byte[] buf = mOutBuffer;
        buf[end++] = typeCode;
        cLongArrayBEHandle.set(buf, end, skeleton.id);
        cLongArrayBEHandle.set(buf, end + 8, skeleton.typeId());
        mOutEnd = end + 16;
    }

    @Override
    public void recycle() throws IOException {
        if (RECYCLE_CLOSE && mMode == M_CLIENT) {
            close();
            return;
        }

        try {
            // Not a perfect detection technique, but it should help identify bugs. Note that
            // this check isn't valid on the server side. If the peer client has already
            // recycled the pipe, then input might be available already for the next request.
            if (mMode == M_CLIENT && available() != 0) {
                throw new IllegalStateException("Pipe has pending input");
            }
            tryRecycle();
        } catch (IllegalStateException e) {
            try {
                close();
            } catch (Exception e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }

        CoreSession session = mSession;
        if (session != null) {
            session.recycleConnection(this);
        } else {
            close();
        }
    }

    /**
     * @param ex can be null
     */
    @Override
    void close(IOException ex) throws IOException {
        CoreSession session = mSession;
        if (session == null) {
            super.close(ex);
        } else {
            session.closeConnection(this);
            if (ex != null) {
                try {
                    session.checkClosed();
                } catch (IOException e2) {
                    e2.addSuppressed(ex);
                    throw e2;
                }
            }
        }
    }

    /**
     * Forcibly close the connection without attempting to remove it from the session. Should
     * only be called by CoreSession.
     */
    void doClose() {
        try {
            super.close(null);
        } catch (IOException e) {
            // Ignore.
        }
    }

    @Override
    public boolean isOpen() {
        CoreSession session = mSession;
        return session == null ? super.isOpen() : session.isConnectionOpen(this);
    }
}
