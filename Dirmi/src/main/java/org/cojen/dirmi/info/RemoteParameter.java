/*
 *  Copyright 2006-2010 Brian S O'Neill
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

package org.cojen.dirmi.info;

import java.io.Serializable;

/**
 * Describes a remote method parameter.
 *
 * @author Brian S O'Neill
 */
public interface RemoteParameter<T> extends Serializable {
    Class<T> getType();

    /**
     * Returns true if parameter does not need to be serialized such that it
     * can be back referenced. If parameter is an array, elements of array must
     * be sharable.
     */
    boolean isUnshared();

    /**
     * Returns true if parameter defines the runtime timeout for the method.
     */
    boolean isTimeout();

    /**
     * Returns true if parameter defines the runtime timeout unit for the method.
     */
    boolean isTimeoutUnit();

    boolean equalTypes(RemoteParameter other);
}
