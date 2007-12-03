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

import java.util.concurrent.TimeUnit;

import dirmi.io.Connection;
import dirmi.io.QueuedBroker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ServerSocketBroker extends QueuedBroker {
    private final byte[] mSessionId;

    public ServerSocketBroker(byte[] sessionId) {
        sessionId = sessionId.clone();
        sessionId[0] |= 0x80;
        mSessionId = sessionId;
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

    private void ackConnect(Connection con) throws IOException {
        con.getOutputStream().write(mSessionId);
        con.getOutputStream().flush();
    }
}
