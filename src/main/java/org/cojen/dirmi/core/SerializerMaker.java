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

import java.io.InvalidClassException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Serializer;

/**
 * @author Brian S O'Neill
 * @see Serializer#simple
 */
public final class SerializerMaker {
    private static final SoftCache<Class<?>, Serializer> cCache = new SoftCache<>();

    public static Serializer forClass(Class<?> type) {
        var serializer = cCache.get(type);

        if (serializer == null) synchronized (cCache) {
            serializer = cCache.get(type);
            if (serializer == null) {
                serializer = make(type);
                cCache.put(type, serializer);
            }
        }

        return (Serializer) serializer;
    }

    private static Serializer make(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            throw new IllegalArgumentException("Not public: " + type.getName());
        }
        if (type.isRecord()) {
            return makeForRecord(type);
        } else {
            return makeForClass(type);
        }
    }

    private static Serializer makeForRecord(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();

        Class<?>[] paramTypes =
            Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);

        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        }

        if (!Modifier.isPublic(ctor.getModifiers())) {
            throw new IllegalArgumentException("No public constructor: " + type.getName());
        }

        long version = hashMix(0, type.getName());

        for (RecordComponent comp : components) {
            if (!Modifier.isPublic(comp.getAccessor().getModifiers())) {
                throw new IllegalArgumentException
                    ("Not public: " + type.getName() + "." + comp.getName());
            }
            version = hashMix(version, comp.getName());
            version = hashMix(version, comp.getType().descriptorString());
        }

        ClassMaker cm = begin(type);

        MethodMaker writeMaker = cm.addMethod(null, "write", Pipe.class, Object.class).public_();
        var writePipeVar = writeMaker.param(0);
        var writeRecordVar = writeMaker.param(1).cast(type);

        MethodMaker readMaker = cm.addMethod(Object.class, "read", Pipe.class).public_();
        var readPipeVar = readMaker.param(0);

        writePipeVar.invoke("writeLong", version);

        versionCheck(type, readPipeVar, version);

        var readParamVars = new Variable[components.length];

        for (int i=0; i<components.length; i++) {
            RecordComponent comp = components[i];
            CoreUtils.writeParam(writePipeVar, writeRecordVar.invoke(comp.getName()));
            CoreUtils.readParam(readPipeVar, readParamVars[i] = readMaker.var(paramTypes[i]));
        }

        readMaker.return_(readMaker.new_(type, (Object[]) readParamVars));

        MethodHandles.Lookup lookup = cm.finishLookup();

        try {
            return (Serializer) lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class)).invoke();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private static Serializer makeForClass(Class<?> type) {
        Constructor<?> ctor;
        try {
            ctor = type.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No public no-arg constructor: " + type.getName());
        }

        Field[] fields = type.getFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));

        long version = hashMix(0, type.getName());

        for (int i=0; i<fields.length; i++) {
            Field field = fields[i];
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
                fields[i] = null;
            } else {
                version = hashMix(version, field.getName());
                version = hashMix(version, field.getType().descriptorString());
            }
        }

        ClassMaker cm = begin(type);

        MethodMaker writeMaker = cm.addMethod(null, "write", Pipe.class, Object.class).public_();
        var writePipeVar = writeMaker.param(0);
        var writeObjectVar = writeMaker.param(1).cast(type);

        MethodMaker readMaker = cm.addMethod(Object.class, "read", Pipe.class).public_();
        var readPipeVar = readMaker.param(0);

        writePipeVar.invoke("writeLong", version);

        versionCheck(type, readPipeVar, version);

        var readObjectVar = readMaker.new_(type);

        for (int i=0; i<fields.length; i++) {
            Field field = fields[i];
            if (field != null) {
                String name = field.getName();
                CoreUtils.writeParam(writePipeVar, writeObjectVar.field(name));
                CoreUtils.readParam(readPipeVar, readObjectVar.field(name));
            }
        }

        readMaker.return_(readObjectVar);

        MethodHandles.Lookup lookup = cm.finishLookup();

        try {
            return (Serializer) lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class)).invoke();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private static ClassMaker begin(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("java.")) {
            name = null;
        }

        ClassMaker cm = ClassMaker.begin(name).implement(Serializer.class);

        cm.addConstructor();

        MethodMaker mm = cm.addMethod(Set.class, "supportedTypes").public_();
        mm.return_(mm.var(Set.class).invoke("of", type));

        return cm;
    }

    private static void versionCheck(Class<?> type, Variable pipeVar, long version) {
        MethodMaker mm = pipeVar.methodMaker();
        var versionVar = pipeVar.invoke("readLong");
        Label versionMatch = mm.label();
        versionVar.ifEq(version, versionMatch);
        var cnameVar = mm.var(Class.class).set(type).invoke("getName");
        mm.new_(InvalidClassException.class, cnameVar, "Serializer version mismatch").throw_();
        versionMatch.here();
    }

    private static long hashMix(long hash, String str) {
        for (int i=0; i<str.length(); i++) {
            hash = hash * 31 + str.charAt(i);
        }
        return hash;
    }
}
