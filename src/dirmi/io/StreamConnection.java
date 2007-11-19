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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

/**
 * Basic connection implementation that wraps two streams.
 *
 * @author Brian S O'Neill
 */
public class StreamConnection implements Connection {
    private final InputStream mIn;
    private final OutputStream mOut;

    private volatile String mLocalAddress;
    private volatile String mRemoteAddress;

    public StreamConnection(InputStream in, OutputStream out) {
        mIn = in;
        mOut = out;
    }

    public StreamConnection(InputStream in, OutputStream out,
                            String localAddress, String remoteAddress)
    {
        mIn = in;
        mOut = out;
        mLocalAddress = localAddress;
        mRemoteAddress = remoteAddress;
    }

    public InputStream getInputStream() {
        return mIn;
    }

    /**
     * Always returns -1, indicating infinite timeout.
     */
    public long getReadTimeout() {
        return -1;
    }

    public TimeUnit getReadTimeoutUnit() {
        return TimeUnit.NANOSECONDS;
    }

    /**
     * Ignored -- timeout is always infinite.
     */
    public void setReadTimeout(long time, TimeUnit unit) {
    }

    public OutputStream getOutputStream() {
        return mOut;
    }

    /**
     * Always returns -1, indicating infinite timeout.
     */
    public long getWriteTimeout() {
        return -1;
    }

    public TimeUnit getWriteTimeoutUnit() {
        return TimeUnit.NANOSECONDS;
    }

    /**
     * Ignored -- timeout is always infinite.
     */
    public void setWriteTimeout(long time, TimeUnit unit) {
    }

    public String getLocalAddressString() {
        return mLocalAddress;
    }

    public String getRemoteAddressString() {
        return mRemoteAddress;
    }

    public void close() throws IOException {
        mOut.close();
        mIn.close();
    }
}
