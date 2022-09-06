/*
 *  Copyright 2022 Cojen.org
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
 * Base class for Stubs and Skeletons.
 *
 * @author Brian S O'Neill
 * @see ItemMap
 * @see IdGenerator
 */
class Item {
    // Remote object id.
    protected final long id;

    // ItemMap collision chain.
    Item mNext;

    Item(long id) {
        this.id = id;
    }
}