/*
 *  Copyright 2008-2010 Brian S O'Neill
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

package org.cojen.dirmi;

/**
 * Various {@link Asynchronous} and {@link Batched} calling modes.
 *
 * @author Brian S O'Neill
 */
public enum CallMode {
    /**
     * Send the request to the remote endpoint, but don't immediately flush the
     * channel. If asynchronous method uses a {@link Pipe}, flushing the pipe
     * ensures the request is immediately sent. Otherwise, the request is
     * eventually sent when the channel is reused or the session is {@link
     * Session#flush flushed}.
     */
    EVENTUAL,

    /**
     * Send the request to the remote endpoint and immediately flush the
     * channel. This is the default {@link Asynchronous} calling mode.
     */
    IMMEDIATE,

    /**
     * Immediately send the request to the remote endpoint and wait for
     * acknowledgement that it was received.
     */
    ACKNOWLEDGED,

    /**
     * Mode applicable to {@link Pipe Pipes} which more efficiently uses
     * network resources than a fully bidirectional pipe.
     */
    REQUEST_REPLY,
}
