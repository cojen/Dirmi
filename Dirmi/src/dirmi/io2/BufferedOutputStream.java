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

package dirmi.io2;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Replacement for {@link java.io.BufferedOutputStream} which does a better job
 * of buffer packing. The intent is to reduce the amount of packets sent over a
 * network.
 *
 * @author Brian S O'Neill
 */
public class BufferedOutputStream extends AbstractBufferedOutputStream {
    private final OutputStream mOut;

    BufferedOutputStream(OutputStream out) {
        mOut = out;
    }

    @Override
    public synchronized void flush() throws IOException {
        super.flush();
        mOut.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        super.flush();
        mOut.close();
    }

    @Override
    protected void doWrite(byte[] buffer, int offset, int length) throws IOException {
        mOut.write(buffer, offset, length);
    }
}
