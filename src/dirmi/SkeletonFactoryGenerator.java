/*
 *  Copyright 2006 Brian S O'Neill
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

package dirmi;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import java.rmi.Remote;

import java.util.Map;

import cojen.util.SoftValuedHashMap;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SkeletonFactoryGenerator<R extends Remote> {
    private static final Map<Class<?>, SkeletonFactory<?>> cCache;

    static {
        cCache = new SoftValuedHashMap();
    }

    /**
     * @param type
     * @throws IllegalArgumentException if remote is null or malformed
     */
    public static <R extends Remote> SkeletonFactory<R> getSkeletonFactory(Class<R> type)
        throws IllegalArgumentException
    {
        synchronized (cCache) {
            SkeletonFactory<R> factory = (SkeletonFactory<R>) cCache.get(type);
            if (factory == null) {
                factory = new SkeletonFactoryGenerator<R>(type).generateFactory();
                cCache.put(type, factory);
            }
            return factory;
        }
    }

    private final Class<R> mType;
    private final RemoteInfo mInfo;

    private SkeletonFactoryGenerator(Class<R> type) {
        mType = type;
        mInfo = RemoteIntrospector.examine(type);
    }

    private SkeletonFactory<R> generateFactory() {
        Class<? extends Skeleton> skeletonClass = generateSkeleton();
        try {
            return new Factory<R>
                (mType, skeletonClass.getConstructor(Remote.class, SkeletonSupport.class));
        } catch (NoSuchMethodException e) {
            NoSuchMethodError nsme = new NoSuchMethodError();
            nsme.initCause(e);
            throw nsme;
        }
    }

    private Class<? extends Skeleton> generateSkeleton() {
        // TODO
        return null;
    }

    private static class Factory<R extends Remote> implements SkeletonFactory<R> {
        private final Class<R> mType;
        private final Constructor<? extends Skeleton> mSkeletonCtor;

        /**
         * @param skeletonCtor (Remote remoteServer, SkeletonSupport support)
         */
        Factory(Class<R> type, Constructor<? extends Skeleton> skeletonCtor) {
            mType = type;
            mSkeletonCtor = skeletonCtor;
        }

        public Class<R> getRemoteType() {
            return mType;
        }

        public Class<? extends Skeleton> getSkeletonClass() {
            return mSkeletonCtor.getDeclaringClass();
        }

        public Skeleton createSkeleton(R remoteServer, SkeletonSupport support) {
            Throwable error;
            try {
                return mSkeletonCtor.newInstance(remoteServer, support);
            } catch (InstantiationException e) {
                error = e;
            } catch (IllegalAccessException e) {
                error = e;
            } catch (InvocationTargetException e) {
                error = e.getCause();
            }
            InternalError ie = new InternalError();
            ie.initCause(error);
            throw ie;
        }
    }
}
