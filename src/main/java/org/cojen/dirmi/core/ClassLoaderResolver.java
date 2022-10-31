/*
 *  Copyright 2009-2022 Cojen.org
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

import org.cojen.dirmi.ClassResolver;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class ClassLoaderResolver implements ClassResolver {
    private final ClassLoader mLoader;
    private final SoftCache<String, Class<?>> mCache;

    public ClassLoaderResolver(ClassLoader loader) {
        mLoader = loader;
        mCache = new SoftCache<>();
    }

    @Override
    public Class<?> resolveClass(String name) throws ClassNotFoundException {
        Class<?> clazz = mCache.get(name);

        if (clazz != null) {
            return clazz;
        }

        synchronized (mCache) {
            if ((clazz = mCache.get(name)) != null) {
                return clazz;
            }
        }

        clazz = Class.forName(name, false, mLoader);

        synchronized (mCache) {
            Class<?> existing = mCache.get(name);
            if (existing != null) {
                return existing;
            }
            mCache.put(name, clazz);
            return clazz;
        }
    }
}
