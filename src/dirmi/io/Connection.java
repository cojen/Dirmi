/*
 *  Copyright 2006 Brian S O'Neill
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

import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Basic interface for a bidirectional I/O connection.
 *
 * @author Brian S O'Neill
 */
public interface Connection extends Closeable {
    InputStream getInputStream() throws IOException;

    /**
     * Returns the timeout for blocking read operations, in milliseconds. If
     * timeout is negative, block timeout is infinite. When a read times out,
     * it throws an InterruptedIOException.
     */
    int getReadTimeout() throws IOException;

    /**
     * Set the timeout for blocking read operations, in milliseconds. If
     * timeout is negative, block timeout is infinite. When a read times out,
     * it throws an InterruptedIOException.
     */
    void setReadTimeout(int timeoutMillis) throws IOException;

    OutputStream getOutputStream() throws IOException;

    /**
     * Returns the timeout for blocking write operations, in milliseconds. If
     * timeout is negative, block timeout is infinite. When a write times out,
     * it throws an InterruptedIOException.
     */
    int getWriteTimeout() throws IOException;

    /**
     * Set the timeout for blocking write operations, in milliseconds. If
     * timeout is negative, block timeout is infinite. When a write times out,
     * it throws an InterruptedIOException.
     */
    void setWriteTimeout(int timeoutMillis) throws IOException;

    /**
     * Returns the full local address of the connection. This method should not
     * block when called. If host name resolution may block, return an
     * unresolved name and resolve in a background thread for later requests.
     *
     * @return local address or null if unknown
     */
    String getLocalAddressString();

    /**
     * Returns the full remote address of the connection. This method should not
     * block when called. If host name resolution may block, return an
     * unresolved name and resolve in a background thread for later requests.
     *
     * @return remote address or null if unknown
     */
    String getRemoteAddressString();
}
