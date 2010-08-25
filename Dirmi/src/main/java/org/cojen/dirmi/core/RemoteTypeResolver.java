/*
 *  Copyright 2009-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.lang.reflect.Method;

import java.io.IOException;
import java.io.Serializable;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.cojen.classfile.MethodDesc;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.RuntimeClassFile;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.KeyFactory;
import org.cojen.util.SoftValuedHashMap;

import org.cojen.dirmi.ClassResolver;

import org.cojen.dirmi.info.RemoteInfo;
import org.cojen.dirmi.info.RemoteIntrospector;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class RemoteTypeResolver implements ClassResolver {
    private static final AtomicReferenceFieldUpdater<RemoteTypeResolver, ClassResolver>
        classResolverUpdater = AtomicReferenceFieldUpdater.newUpdater
        (RemoteTypeResolver.class, ClassResolver.class, "mClassResolver");

    private volatile ClassResolver mClassResolver;

    private Map<Object, Class> mSyntheticRemoteTypes;
    private Map<Object, Class> mSyntheticSerializableTypes;

    RemoteTypeResolver() {
    }

    @Override
    public Class<?> resolveClass(String name) throws IOException, ClassNotFoundException {
        Class<?> clazz;
        if (mClassResolver == null || (clazz = mClassResolver.resolveClass(name)) == null) {
            clazz = ClassLoaderResolver.DEFAULT.resolveClass(name);
        }
        return clazz;
    }

    Class<?> resolveClass(String name, long serialVersionUID)
        throws IOException, ClassNotFoundException
    {
        try {
            return resolveClass(name);
        } catch (ClassNotFoundException e) {
            if (name.startsWith("java.")) {
                // Cannot create a fake class without a security exception.
                throw e;
            }
            return syntheticSerializableClass(name, serialVersionUID);
        }
    }

    void setClassResolver(ClassResolver resolver) {
        if (resolver == null) {
            resolver = ClassLoaderResolver.DEFAULT;
        }
        if (!classResolverUpdater.compareAndSet(this, null, resolver)) {
            throw new IllegalStateException("ClassResolver is already set");
        }
    }

    Class<?> resolveRemoteType(RemoteInfo info) throws IOException {
        Class type;
        try {
            type = resolveClass(info.getName());
            if (!type.isInterface() || !Remote.class.isAssignableFrom(type)) {
                type = null;
            }
        } catch (ClassNotFoundException e) {
            type = null;
        }

        if (type != null) {
            return type;
        }

        // Possibly create a synthetic type to match what server offers. Use
        // TreeSet to ensure consistent ordering.
        Set<String> nameSet = new TreeSet<String>(info.getInterfaceNames());
        Set<Class> ifaceSet = new LinkedHashSet<Class>(nameSet.size());

        for (String name : nameSet) {
            if (name.equals(Remote.class.getName())) {
                continue;
            }
            try {
                Class iface = resolveClass(name);
                RemoteIntrospector.examine(iface);
                ifaceSet.add(iface);
            } catch (ClassNotFoundException e) {
                continue;
            } catch (IllegalArgumentException e) {
                continue;
            }
        }

        if (ifaceSet.isEmpty()) {
            return Remote.class;
        }

        return syntheticRemoteType(info.getName(), ifaceSet);
    }

    private synchronized Class<?> syntheticRemoteType(String typeName, Set<Class> ifaceSet) {
        Object key = KeyFactory.createKey(new Object[] {typeName, ifaceSet});

        if (mSyntheticRemoteTypes == null) {
            mSyntheticRemoteTypes = new SoftValuedHashMap<Object, Class>();
        } else {
            Class type = mSyntheticRemoteTypes.get(key);
            if (type != null) {
                return type;
            }
        }

        RuntimeClassFile cf = CodeBuilderUtil.createRuntimeClassFile(typeName, new Loader());
        cf.setModifiers(Modifiers.PUBLIC.toInterface(true));
        cf.addInterface(Remote.class);
        TypeDesc exType = TypeDesc.forClass(RemoteException.class);

        Set<String> methodsAdded = new HashSet<String>();

        for (Class iface : ifaceSet) {
            cf.addInterface(iface);

            for (Method method : iface.getMethods()) {
                String name = method.getName();
                MethodDesc desc = MethodDesc.forMethod(method);
                String sig = desc.toMethodSignature(name);

                if (methodsAdded.add(sig)) {
                    cf.addMethod(Modifiers.PUBLIC_ABSTRACT, name,
                                 desc.getReturnType(), desc.getParameterTypes())
                        .addException(exType);
                }
            }
        }

        Class type = cf.defineClass();
        mSyntheticRemoteTypes.put(key, type);
        return type;
    }

    private synchronized Class<?> syntheticSerializableClass(String name, long serialVersionUID) {
        Object key = KeyFactory.createKey(new Object[] {name, serialVersionUID});

        if (mSyntheticSerializableTypes == null) {
            mSyntheticSerializableTypes = new SoftValuedHashMap<Object, Class>();
        } else {
            Class clazz = mSyntheticSerializableTypes.get(key);
            if (clazz != null) {
                return clazz;
            }
        }

        RuntimeClassFile cf = new RuntimeClassFile(name, null, new Loader(), null, true);
        cf.setModifiers(Modifiers.PUBLIC);
        cf.addInterface(Serializable.class);
        cf.addField(Modifiers.PRIVATE.toStatic(true).toFinal(true),
                    "serialVersionUID", TypeDesc.LONG).setConstantValue(serialVersionUID);

        Class clazz = cf.defineClass();
        mSyntheticSerializableTypes.put(key, clazz);
        return clazz;
    }

    private class Loader extends ClassLoader {
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                return RemoteTypeResolver.this.resolveClass(name);
            } catch (IOException e) {
                throw new ClassNotFoundException(name + ", " + e);
            }
        }
    }
}
