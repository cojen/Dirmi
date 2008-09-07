/*
 *  Copyright 2008 Brian S O'Neill
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

package dirmi.nio2;

import java.nio.ByteBuffer;

import java.util.Random;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestPool {
    public static void main(String[] args) throws Exception {
        final int count = Integer.parseInt(args[0]);

        final BufferPool pool = new BufferPool();

        while (true) {
            {
                Random rnd = new Random(234810);
                long start = System.currentTimeMillis();
                for (int i=0; i<count; i++) {
                    ByteBuffer buffer = pool.get(rnd.nextInt(65536) + 1);
                    pool.yield(buffer);
                }
                long end = System.currentTimeMillis();
                System.out.println("Pool: " + (end - start));
            }

            /*
            {
                Random rnd = new Random(234810);
                long start = System.currentTimeMillis();
                for (int i=0; i<count; i++) {
                    ByteBuffer buffer = ByteBuffer.allocate(rnd.nextInt(65536) + 1);
                }
                long end = System.currentTimeMillis();
                System.out.println("Allocate: " + (end - start));
            }
            */
        }
    }
}
