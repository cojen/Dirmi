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
import java.io.InputStream;

/**
 * Replacement for {@link java.io.BufferedInputStream} which does not have the
 * block forever on read bug. Marking is not supported.
 *
 * @author Brian S O'Neill
 */
public class BufferedInputStream extends AbstractBufferedInputStream {
    private final InputStream mIn;

    public BufferedInputStream(InputStream in) {
        super();
        mIn = in;
    }

    public BufferedInputStream(InputStream in, int size) {
        super(size);
        mIn = in;
    }

    @Override
    public synchronized void close() throws IOException {
        mIn.close();
    }

    @Override
    protected int doRead(byte[] buffer, int offset, int length) throws IOException {
        return mIn.read(buffer, offset, length);
    }

    @Override
    protected long doSkip(long n) throws IOException {
        return mIn.skip(n);
    }
}
