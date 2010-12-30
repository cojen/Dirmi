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

import de.root1.simon.Lookup;
import de.root1.simon.Simon;

import rpcPerf.PerfTest;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SimonClient implements rpcPerf.Client {
    public static void main(String[] args) throws Exception {
        PerfTest test = new PerfTest(new SimonClient(args[0], Integer.parseInt(args[1])));
        test.test(Integer.parseInt(args[2]));
    }

    private final String mHost;
    private final int mPort;

    public SimonClient(String host, int port) {
        mHost = host;
        mPort = port;
    }

    public void test(int iterations) throws Exception {
        RemoteInterface remote = null;
        Lookup lookup = Simon.createNameLookup(mHost, mPort);
        try {
            remote = (RemoteInterface) lookup.lookup("test");
            for (int i=0; i<iterations; i++) {
                remote.doIt();
            }
        } finally {
            if (remote != null) {
                lookup.release(remote);
            }
        }
    }
}

