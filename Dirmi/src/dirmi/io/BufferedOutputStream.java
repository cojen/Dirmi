/*
 *  Copyright 2007 Brian S O'Neill
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
import java.io.OutputStream;

/**
 * Replacement for {@link java.io.BufferedOutputStream} which does a better job
 * of buffer packing. The intent is to reduce the amount of packets sent over a
 * network.
 *
 * <p>An additional feature is the ability to efficiently transfer bytes from
 * an InputStream to the buffer, without requiring an intermediate buffer.
 *
 * @author Brian S O'Neill
 */
public class BufferedOutputStream extends AbstractBufferedOutputStream {
    private final OutputStream mOut;
    
    public BufferedOutputStream(OutputStream out) {
        super();
        mOut = out;
    }

    public BufferedOutputStream(OutputStream out, int size) {
        super(size);
        mOut = out;
    }

    @Override
    public synchronized void flush() throws IOException {
        drain();
        mOut.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        drain();
        mOut.close();
    }

    @Override
    protected void doWrite(byte[] buffer, int offset, int length) throws IOException {
        mOut.write(buffer, offset, length);
    }
}
