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

import java.io.Serializable;

/**
 * used by CoreObjectOutputStream and CoreObjectInputStream.
 *
 * @author Brian S O'Neill
 */
class MarshalledSkeleton implements Serializable {
    final long id;
    final long typeId;
    final byte[] infoBytes;

    MarshalledSkeleton(long id, long typeId, byte[] infoBytes) {
        this.id = id;
        this.typeId = typeId;
        this.infoBytes = infoBytes;
    }
}