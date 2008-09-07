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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Concurrent pool of ByteBuffers.
 *
 * @author Brian S O'Neill
 */
class BufferPool {
    private final ConcurrentLinkedQueue<ByteBuffer>[] mQueues;

    BufferPool() {
        mQueues = new ConcurrentLinkedQueue[17];
        for (int i=0; i<mQueues.length; i++) {
            // Performance testing showed that the garbage collector
            // outperformed the pool for allocations less than 256..512
            // bytes. For this reason, small buffer requests are not pooled.
            if (i >= 8) {
                mQueues[i] = new ConcurrentLinkedQueue<ByteBuffer>();
            }
        }
    }

    ByteBuffer get(int minCapacity) {
        int queueNum = queueNum(minCapacity);
        ConcurrentLinkedQueue<ByteBuffer> queue = selectQueue(queueNum);
        if (queue != null) {
            ByteBuffer buffer = queue.poll();
            return buffer != null ? buffer : ByteBuffer.allocateDirect(1 << queueNum);
        } else {
            // Since this will be managed by garbage collector, don't allocate direct.
            return ByteBuffer.allocate(minCapacity);
        }
    }

    void yield(ByteBuffer buffer) {
        buffer.clear();
        ConcurrentLinkedQueue<ByteBuffer> queue = selectQueue(queueNum(buffer.capacity()));
        if (queue != null) {
            queue.add(buffer);
        }
    }

    private static int queueNum(int minCapacity) {
        return 32 - Integer.numberOfLeadingZeros(minCapacity - 1);
    }

    private ConcurrentLinkedQueue<ByteBuffer> selectQueue(int queueNum) {
        ConcurrentLinkedQueue<ByteBuffer>[] queues = mQueues;
        return queueNum < queues.length ? queues[queueNum] : null;
    }
}
