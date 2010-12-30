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

package rpcPerf;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class PerfTest implements Client {
    private final Client mClient;

    public PerfTest(Client client) {
        mClient = client;
    }

    public void test(int iterations) throws Exception {
        // Warmup.
        mClient.test(1000);
        print("starting");

        long start = System.currentTimeMillis();
        mClient.test(iterations);
        long end = System.currentTimeMillis();
        double rps = 1000.0 * iterations / (end - start);
        print("requests per second: " + rps);
    }

    private void print(String message) {
        System.out.println(mClient.getClass().getName() + ": " + message);
    }
}
