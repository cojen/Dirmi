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
public interface MessageReceiver<S> {
    /**
     * Called at most once as soon as message channel has been established.
     * This method may safely block, and it can interact with the sender too.
     */
    void established(MessageSender sender);

    /**
     * Called when a message is fully or partially received. In either case,
     * this method must not block -- it may only copy the message from the
     * buffer. Only when the process method is called may the receiver process
     * the message and potentially block. Messages are received in order, but
     * they may be processed out of order.
     *
     * <p>Only one thread at a time will call receive, and it will be the exact
     * same thread as partial pieces are received. Multiple messages may
     * received before being processed.
     *
     * @param state state which was returned by previous invocation of receive,
     * or null if start of message
     * @param totalSize total size of message
     * @param offset message offset; is zero if start of message
     * @param buffer position is set at the start or continuation of the
     * message, remaining is amount received
     * @return state object which is passed again to receive and process
     * methods
     */
    S receive(S state, int totalSize, int offset, ByteBuffer buffer);

    /**
     * Called after a message has been completely received. This method may
     * safely block, and it can interact with the sender too. While this method
     * is executing, other messages may be received and processed concurrently.
     *
     * @param state object which was returned by receive method
     * @param sender use to send reply messages
     */
    void process(S state, MessageSender sender);

    /**
     * Called when message channel is closed. This method may safely block.
     */
    void closed();

    /**
     * Called when message channel is closed due to an exception. It may be
     * closed before it is established. This method may safely block.
     */
    void closed(IOException e);
}
