/*
 *  Copyright 2006 Brian S O'Neill
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

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestAtomicIntegerMap {
    public static void main(String[] args) throws Exception {
        final AtomicIntegerMap<String> map = new AtomicIntegerMap<String>();
        //map.set("key", 1);

        class TestThread extends Thread {
            private final String mKey;
            private final int mDelta;
            private final int mCount;

            TestThread(String key, int delta, int count) {
                mKey = key;
                mDelta = delta;
                mCount = count;
            }

            public void run() {
                for (int i=0; i<mCount; i++) {
                    int old = map.getAndAdd(mKey, mDelta);
                    /*
                    if (i % 500000 == 0) {
                        System.out.println("***** " + old);
                    }
                    */
                }
            }
        }

        while (true) {
            int threadCount = 10;
            int loopCount = 1000000;
            
            Thread[] threads = new Thread[threadCount];
            
            for (int i=0; i<threads.length; i++) {
                int delta = (i % 2) * 2 - 1;
                threads[i] = new TestThread("key", delta, loopCount);
            }
            
            for (int i=0; i<threads.length; i++) {
            threads[i].start();
            }
            
            for (int i=0; i<threads.length; i++) {
                threads[i].join();
            }
            
            System.out.println(map.get("key"));
        }
    }
}
