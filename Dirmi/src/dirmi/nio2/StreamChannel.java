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

package dirmi.nio2;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.Executor;

import java.nio.channels.Channel;

/**
 * Basic interface for a blocking bidirectional I/O channel.
 *
 * @author Brian S O'Neill
 */
public interface StreamChannel extends Channel, Executor {
    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    /**
     * Asynchronously calls the given task as soon as data can be read from the
     * channel.
     */
    void executeWhenReadable(StreamTask task);

    /**
     * @return local address or null if unknown
     */
    Object getLocalAddress();

    /**
     * @return remote address or null if unknown
     */
    Object getRemoteAddress();
}
