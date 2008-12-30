/*
 *  Copyright 2008 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Replacement for {@link java.io.BufferedOutputStream} which does a better job
 * of buffer packing. The intent is to reduce the amount of packets sent over a
 * network. Any exception thrown by the underlying stream causes it to be
 * automatically closed.
 *
 * @author Brian S O'Neill
 */
class BufferedOutputStream extends AbstractBufferedOutputStream {
    private final OutputStream mOut;

    public BufferedOutputStream(OutputStream out) {
        this(out, 0, DEFAULT_SIZE, 0);
    }

    public BufferedOutputStream(OutputStream out, int prefix, int size, int suffix) {
        super(prefix, size, suffix);
        if (out == null) {
            throw new IllegalArgumentException();
        }
        mOut = out;
    }

    // Copy constructor.
    BufferedOutputStream(BufferedOutputStream out) {
        super(out);
        mOut = out.mOut;
    }

    @Override
    public boolean flush(boolean force) throws IOException {
        try {
            synchronized (this) {
                if (super.flush(force)) {
                    mOut.flush();
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        flush();
        mOut.close();
    }

    @Override
    protected void doFlush(byte[] buffer, int offset, int length) throws IOException {
        try {
            mOut.write(buffer, offset, length);
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Is called without lock held.
     */
    protected void disconnect() {
        try {
            mOut.close();
        } catch (IOException e2) {
            // Ignore.
        }
    }
}
