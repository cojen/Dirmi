/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

import org.cojen.dirmi.Connector;
import org.cojen.dirmi.Session;

import java.io.IOException;

import java.net.Socket;

import javax.net.SocketFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class SecureConnector implements Connector {
    public static final SecureConnector THE = new SecureConnector(null);

    private final SSLContext mContext;

    public SecureConnector(SSLContext context) {
        mContext = context;
    }

    @Override
    public void connect(Session session) throws IOException {
        SocketFactory factory;
        if (mContext == null) {
            factory = SSLSocketFactory.getDefault();
        } else {
            factory = mContext.getSocketFactory();
        }
        Socket s = factory.createSocket();
        s.connect(session.remoteAddress());
        session.connected(s);
    }
}
