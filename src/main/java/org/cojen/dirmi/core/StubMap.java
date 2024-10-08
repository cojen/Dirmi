/*
 *  Copyright 2024 Cojen.org
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

/**
 * 
 *
 * @author Brian S. O'Neill
 */
final class StubMap extends ItemMap<StubInvoker> {
    /**
     * Returns the given invoker, an existing invoker, or a wrapper.
     *
     * @param invoker must be a new instance
     */
    synchronized Stub putAndSelectStub(StubInvoker invoker) {
        Item[] items = mItems;
        int slot = ((int) invoker.id) & (items.length - 1);

        for (Item existing = items[slot]; existing != null; existing = existing.mNext) {
            if (existing.id == invoker.id) {
                Stub selected = ((StubInvoker) existing).select();
                if (selected != null) {
                    return selected;
                }
                break;
            }
        }

        // Must init before calling doPut because upon doing so the invoker can be obtained by
        // other threads.
        Stub selected = invoker.init();

        doPut(items, invoker, slot);

        return selected;
    }
}
