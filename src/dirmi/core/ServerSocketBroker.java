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

package dirmi.core;

import java.io.IOException;
import java.io.OutputStream;

import java.net.InetAddress;

import java.util.concurrent.TimeUnit;

import dirmi.io.Connection;
import dirmi.io.QueuedBroker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ServerSocketBroker extends QueuedBroker {
    private final InetAddress mRemoteAddress;
    private final Identifier mIdentifier;

    public ServerSocketBroker(InetAddress remoteAddress, Identifier id) {
        mRemoteAddress = remoteAddress;
        mIdentifier = id;
    }

    @Override
    public Connection connect() throws IOException {
        Connection con = super.connect();
        ackConnect(con);
        return con;
    }

    @Override
    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        Connection con = super.tryConnect(time, unit);
        if (con != null) {
            ackConnect(con);
        }
        return con;
    }

    public InetAddress getRemoteAddress() {
        return mRemoteAddress;
    }

    private void ackConnect(Connection con) throws IOException {
        OutputStream out = con.getOutputStream();
        mIdentifier.write(out);
        out.flush();
    }
}
