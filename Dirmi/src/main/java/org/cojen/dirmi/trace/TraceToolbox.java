/*
 *  Copyright 2006-2011 Brian S O'Neill
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

package org.cojen.dirmi.trace;

/**
 * Tools for use by a {@link TraceHandler}.
 *
 * @author Brian S O'Neill
 */
public class TraceToolbox {
    private final TracedMethodRegistry mRegistry;

    TraceToolbox(TracedMethodRegistry registry) {
        mRegistry = registry;
    }

    /**
     * Returns a previously registered method.
     *
     * @param mid method identifier
     * @return registered method or null if not found
     */
    public TracedMethod getTracedMethod(int mid) {
        return mRegistry.getTracedMethod(mid);
    }
}
