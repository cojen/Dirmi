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

package org.cojen.dirmi.io;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * Works like ByteArrayOutputStream except it's optimized for a single write.
 *
 * @author Brian S O'Neill
 */
public final class CaptureOutputStream extends OutputStream {
    private byte[] mBytes;
    private ByteArrayOutputStream mBout;

    @Override
    public void write(int b) {
        write(new byte[] {(byte) b});
    }

    @Override
    public void write(byte[] b) {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        if (mBout == null) {
            if (mBytes == null) {
                var bytes = new byte[len];
                System.arraycopy(b, off, bytes, 0, len);
                mBytes = bytes;
                return;
            }
            mBout = new ByteArrayOutputStream(mBytes.length + len);
            mBout.writeBytes(mBytes);
            mBytes = null;
        }

        mBout.write(b, off, len);
    }

    /**
     * Returns a possibly uncopied reference to the captured bytes.
     */
    public byte[] getBytes() {
        return mBout == null ? mBytes : mBout.toByteArray();
    }
}
