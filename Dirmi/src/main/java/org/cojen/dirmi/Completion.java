/*
 *  Copyright 2009-2010 Brian S O'Neill
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

package org.cojen.dirmi;

import java.util.Queue;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A {@code Completion} is a {@link Future} which also allows registration into
 * a completion queue. By polling results from the completion queue (which can
 * be blocking), multiple {@link Asynchronous asynchronous} methods can be
 * tracked. The queue implementation must be thread-safe, and an ideal choice
 * is {@link LinkedBlockingQueue}.
 *
 * @author Brian S O'Neill
 */
public interface Completion<V> extends Future<V> {
    /**
     * Registers this future such that it enqueues itself into the given queue
     * when done. If future is already done by the time register is called, it
     * is immediately enqueued.
     *
     * @param completionQueue thread-safe queue to receive finished
     * asynchronous results
     * @throws IllegalArgumentException if completion queue argument is null
     * @throws IllegalStateException if already registered with a completion queue
     */
    void register(Queue<? super Completion<V>> completionQueue);
}
