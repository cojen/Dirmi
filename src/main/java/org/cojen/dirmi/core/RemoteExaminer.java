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
    /**
     * Returns the remote interface implemented by the given remote object, but doesn't fully
     * validate it.
     *
     * @throws NullPointerException if object is null
     * @throws IllegalArgumentException if more than one remote interface is defined, or if no
     * remote interface is implemented at all
     */
    static Class<?> remoteType(Object obj) {
        return remoteTypeForClass(obj.getClass());
    }

    /**
     * Returns the remote interface implemented by the given class, but doesn't fully validate
     * it.
     *
     * @throws NullPointerException if clazz is null
     * @throws IllegalArgumentException if more than one remote interface is defined, or if no
     * remote interface is implemented at all
     */
    static Class<?> remoteTypeForClass(Class<?> clazz) {
        // Only consider the one that implements Remote.

        // Note when considering result caching: Stub classes refer to the remote type already
        // because they implement it. Therefore, the cache must support weak/soft keys and not
        // just weak/soft values. The call to getInterfaces is already cached (other than the
        // array clone), and so additional caching might not be worth the trouble.

        Class<?> theOne = null;

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

        return theOne;
    }
}
