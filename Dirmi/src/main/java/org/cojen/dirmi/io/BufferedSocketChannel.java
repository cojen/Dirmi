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

package org.cojen.dirmi.io;

import java.io.IOException;

import java.net.Socket;

import java.rmi.Remote;

import java.util.Map;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class BufferedSocketChannel extends SocketChannel {
    BufferedSocketChannel(IOExecutor executor, SimpleSocket socket, Map<Channel, Object> accepted)
        throws IOException
    {
        super(executor, socket, accepted);
    }

    @Override
    public Remote installRecycler(Recycler recycler) {
        return null;
    }

    @Override
    public void setRecycleControl(Remote control) {
    }

    @Override
    BufferedInputStream createInputStream(SimpleSocket socket) throws IOException {
        return new BufferedChannelInputStream(this, socket.getInputStream());
    }

    @Override
    BufferedOutputStream createOutputStream(SimpleSocket socket) throws IOException {
        return new BufferedChannelOutputStream(this, socket.getOutputStream());
    }
}
