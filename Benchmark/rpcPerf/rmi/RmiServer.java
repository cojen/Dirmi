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

package rpcPerf.rmi;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RmiServer implements rpcPerf.Server, RemoteInterface {
    public static void main(String[] args) throws Exception {
        new RmiServer(Integer.parseInt(args[0])).start();
    }

    private final int mPort;

    volatile Registry mRegistry;

    public RmiServer(int port) {
        mPort = port;
    }

    public void start() throws Exception {
        mRegistry = LocateRegistry.createRegistry(mPort);
        UnicastRemoteObject.exportObject(this, mPort);
        Naming.bind("//localhost:" + mPort + "/test", this);
    }

    public void stop() throws Exception {
        UnicastRemoteObject.unexportObject(this, true);
    }

    public void doIt() {
    }
}
