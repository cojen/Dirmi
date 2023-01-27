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

import java.io.IOException;

import org.cojen.dirmi.Connector;
import org.cojen.dirmi.Environment;
import org.cojen.dirmi.Session;

import org.cojen.dirmi.io.PipedInputStream;
import org.cojen.dirmi.io.PipedOutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class LocalConnector implements Connector {
    private final Environment mEnv;

    public LocalConnector(Environment env) {
        mEnv = env;
    }

    @Override
    public void connect(Session session) throws IOException {
        var clientIn = new PipedInputStream();
        var serverOut = new PipedOutputStream(clientIn);

        var clientOut = new PipedOutputStream();
        var serverIn = new PipedInputStream(clientOut);

        session.execute(() -> {
            try {
                mEnv.accepted(null, null, serverIn, serverOut);
            } catch (Throwable e) {
                if (session instanceof CoreSession) {
                    ((CoreSession) session).uncaught(e);
                } else if (mEnv instanceof Engine) {
                    ((Engine) mEnv).uncaught(null, e);
                } else {
                    CoreUtils.rethrow(e);
                }
            }
        });

        session.connected(null, null, clientIn, clientOut);
    }
}
