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

import java.lang.reflect.Modifier;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class RemoteExaminer {
    private static final SoftCache<Class<?>, Class<?>> cCache = new SoftCache<>();

    /**
     * Returns the remote interface implemented by the the given remote object.
     *
     * @throws IllegalArgumentException if object is null or malformed
     */
    static Class<?> remoteType(Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Remote object must not be null");
        }

        Class<?> clazz = obj.getClass();

        Class<?> theOne = cCache.get(clazz);
        if (theOne != null) {
            return theOne;
        }

        synchronized (cCache) {
            theOne = cCache.get(clazz);
            if (theOne != null) {
                return theOne;
            }

            // Only consider the one that implements Remote.

            for (Class<?> iface : clazz.getInterfaces()) {
                if (Modifier.isPublic(iface.getModifiers()) && CoreUtils.isRemote(iface)) {
                    if (theOne != null) {
                        throw new IllegalArgumentException
                            ("At most one Remote interface may be directly implemented: " +
                             clazz.getName());
                    }
                    theOne = iface;
                }
            }

            if (theOne == null) {
                throw new IllegalArgumentException
                    ("No Remote types directly implemented: " + clazz.getName());
            }

            cCache.put(clazz, theOne);
            return theOne;
        }
    }
}
