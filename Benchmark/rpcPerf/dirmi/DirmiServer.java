/*
 *  Copyright 2010 Brian S O'Neill
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

package rpcPerf.dirmi;

import org.cojen.dirmi.Environment;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class DirmiServer implements rpcPerf.Server, RemoteInterface {
    public static void main(String[] args) throws Exception {
        new DirmiServer(Integer.parseInt(args[0])).start();
    }

    private final int mPort;

    private volatile Environment mEnv;

    public DirmiServer(int port) {
        mPort = port;
    }

    public void start() throws Exception {
        mEnv = new Environment();
        mEnv.newSessionAcceptor(mPort).acceptAll(this);
    }

    public void stop() throws Exception {
        mEnv.close();
    }

    public void doIt() {
    }
}
