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

package rpcPerf.simon;

import de.root1.simon.Registry;
import de.root1.simon.Simon;
import de.root1.simon.annotation.SimonRemote;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@SimonRemote(value = {RemoteInterface.class}) 
public class SimonServer implements rpcPerf.Server, RemoteInterface {
    public static void main(String[] args) throws Exception {
        new SimonServer(Integer.parseInt(args[0])).start();
    }

    private final int mPort;

    private volatile Registry mRegistry;

    public SimonServer(int port) {
        mPort = port;
    }

    public void start() throws Exception {
        mRegistry = Simon.createRegistry(mPort);
        mRegistry.bind("test", this);
    }

    public void stop() throws Exception {
        mRegistry.unbind("test");
        mRegistry.stop();
    }

    public void doIt() {
    }
}
