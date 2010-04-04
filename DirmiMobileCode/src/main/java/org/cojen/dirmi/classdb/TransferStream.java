/*
 *  Copyright 2009-2010 Brian S O'Neill
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

package org.cojen.dirmi.classdb;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TransferStream extends InputStream {
    private final InputStream in;
    private final OutputStream out;
    int count;

    TransferStream(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            count++;
            out.write(b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int amt = in.read(b, off, len);
        if (amt > 0) {
            count += amt;
            out.write(b, off, amt);
        }
        return amt;
    }

    @Override
    public long skip(long n) throws IOException {
        return 0;
    }
}
