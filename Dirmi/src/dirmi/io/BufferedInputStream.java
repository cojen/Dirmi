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

package dirmi.io;

import java.io.InputStream;
import java.io.IOException;

/**
 * Replacement for {@link java.io.BufferedInputStream} which does not have the
 * block forever on read bug. Marking is not supported.
 *
 * @author Brian S O'Neill
 */
public class BufferedInputStream extends AbstractBufferedInputStream {
    private final InputStream mIn;

    BufferedInputStream(InputStream in) {
        mIn = in;
    }

    @Override
    public synchronized int available() throws IOException {
        int available = super.available();
        if (available > 0) {
            return available;
        }
        return mIn.available();
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
