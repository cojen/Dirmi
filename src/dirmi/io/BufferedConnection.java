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
 * 
 *
 * @author Brian S O'Neill
 */
public class BufferedConnection implements Connection {
    private final Connection mCon;
    private final InputStream mIn;
    private final OutputStream mOut;

    public BufferedConnection(Connection con) throws IOException {
        mCon = con;
        mIn = new BufferedInputStream(con.getInputStream());
        mOut = new BufferedOutputStream(con.getOutputStream());
    }

    public InputStream getInputStream() throws IOException {
        return mIn;
    }

    public long getReadTimeout() throws IOException {
        return mCon.getReadTimeout();
    }

    public TimeUnit getReadTimeoutUnit() throws IOException {
        return mCon.getReadTimeoutUnit();
    }

    public void setReadTimeout(long time, TimeUnit unit) throws IOException {
        mCon.setReadTimeout(time, unit);
    }

    public OutputStream getOutputStream() throws IOException {
        return mOut;
    }

    public long getWriteTimeout() throws IOException {
        return mCon.getWriteTimeout();
    }

    public TimeUnit getWriteTimeoutUnit() throws IOException {
        return mCon.getWriteTimeoutUnit();
    }

    public void setWriteTimeout(long time, TimeUnit unit) throws IOException {
        mCon.setWriteTimeout(time, unit);
    }

    public String getLocalAddressString() {
        return mCon.getLocalAddressString();
    }

    public String getRemoteAddressString() {
        return mCon.getRemoteAddressString();
    }

    public void close() throws IOException {
        mCon.close();
    }
}
