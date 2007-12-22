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

import java.util.concurrent.TimeUnit;

/**
 * Defines timeout configuration for write operations.
 *
 * @author Brian S O'Neill
 */
public interface WriteTimeout {
    /**
     * Returns the timeout for blocking write operations. If timeout is
     * negative, blocking timeout is infinite. When a write times out, it
     * throws an InterruptedIOException.
     */
    long getWriteTimeout() throws IOException;

    TimeUnit getWriteTimeoutUnit() throws IOException;

    /**
     * Set the timeout for blocking write operations. If timeout is negative,
     * blocking timeout is infinite. When a write times out, it throws an
     * InterruptedIOException.
     */
    void setWriteTimeout(long time, TimeUnit unit) throws IOException;
}
