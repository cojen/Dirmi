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

import java.io.ObjectInputFilter;

import org.cojen.dirmi.Serialized;

/**
 * Parses and caches @Serialized filters.
 *
 * @author Brian S O'Neill
 */
final class SerializedFilter {
    private static final SoftCache<String, ObjectInputFilter> cCache = new SoftCache<>();

    static ObjectInputFilter filterFor(Serialized ann) {
        String originalPattern = ann.filter();

        ObjectInputFilter filter = cCache.get(originalPattern);

        if (filter == null) {
            synchronized (cCache) {
                filter = cCache.get(originalPattern);

                if (filter == null) {
                    String pattern = originalPattern;

                    if (!pattern.contains("!*")) {
                        pattern = pattern + ';' + "!*";
                    }

                    pattern = MarshalledStub.class.getName() + ';'
                        + MarshalledSkeleton.class.getName() + ';' + pattern;

                    try {
                        filter = ObjectInputFilter.Config.createFilter(pattern);
                    } catch (IllegalArgumentException e) {
                        if (pattern != originalPattern) {
                            // Try to report only the original pattern in the exception.
                            ObjectInputFilter.Config.createFilter(originalPattern);
                        }
                        throw e;
                    }

                    cCache.put(originalPattern, filter);
                }
            }
        }

        return filter;
    }
}
