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

import java.io.IOException;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
final class StubMap extends ItemMap<StubInvoker> {
    /**
     * Returns a new invoker, an existing invoker, or a wrapper.
     *
     * @param factory used when a new invoker must be created
     * @param session used when a new invoker must be created
     * @param pipe used to read or skip optional remote object data; pass null if unavailable
     * @throws IOException if failed to read remote object data
     */
    Stub findStub(long id, StubFactory factory, CoreSession session, CorePipe pipe)
        throws IOException
    {
        StubInvoker invoker;
        Stub selected;

        existing: {
            synchronized (this) {
                Item[] items = mItems;
                int slot = ((int) id) & (items.length - 1);

                for (Item existing = items[slot]; existing != null; existing = existing.mNext) {
                    if (existing.id == id) {
                        invoker = ((StubInvoker) existing);
                        selected = invoker.select();
                        if (selected != null) {
                            break existing;
                        }
                        break;
                    }
                }

                invoker = factory.newStub(id, session.stubSupport());

                // Must init first because upon calling doPut the invoker can be obtained by
                // other threads.
                selected = invoker.init();

                doPut(items, invoker, slot);
            }

            // Read any data fields outside the synchronized block.
            factory.readDataAndUnlatch(invoker, pipe);

            return selected;
        }

        selected.invoker().incTransportCount();

        // Read or skip any data fields outside the synchronized block.
        factory.readOrSkipData(invoker, pipe);

        return selected;
    }
}
