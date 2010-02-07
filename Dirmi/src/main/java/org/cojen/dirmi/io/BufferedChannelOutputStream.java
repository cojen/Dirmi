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

package org.cojen.dirmi.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class BufferedChannelOutputStream extends BufferedOutputStream {
    private final Channel mChannel;

    BufferedChannelOutputStream(Channel channel, OutputStream out) {
        super(out);
        mChannel = channel;
    }

    BufferedChannelOutputStream(Channel channel, OutputStream out, int size) {
        super(out, size);
        mChannel = channel;
    }

    @Override
    public void close() throws IOException {
        mChannel.close();
    }

    @Override
    public void disconnect() {
        mChannel.disconnect();
    }
}
