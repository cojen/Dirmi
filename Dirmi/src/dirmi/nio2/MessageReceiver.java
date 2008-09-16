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

import java.io.IOException;

import java.nio.ByteBuffer;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface MessageReceiver {
    /**
     * Called when a message is fully or partially received. In either case,
     * this method must not block -- it may only copy the message from the
     * buffer. Only when the process method is called may the receiver process
     * the message and potentially block. Messages are received in order, but
     * they may be processed out of order.
     *
     * <p>This method may optionally return a new MessageReceiver in order to
     * immediately receive more messages from channel. This is generally
     * done only once per received message.
     *
     * @param totalSize total size of message
     * @param offset message offset; is zero if start of message
     * @param buffer position is set at the start or continuation of the
     * message, remaining is amount received
     * @return receiver of next message, or null
     */
    MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer);

    /**
     * Called after the message has been completely received and can be
     * processed. This method may safely block, and it can interact with the
     * channel too. Also note that this method may be called by a different
     * thread than receive was called by.
     */
    void process();

    /**
     * Called when channel is closed. This method may safely block.
     */
    void closed();

    /**
     * Called when channel is closed due to an exception. This method may
     * safely block.
     */
    void closed(IOException e);
}
