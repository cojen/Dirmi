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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
    private static final SoftCache<Object, Serializer> cCache = new SoftCache<>();

    public static Serializer forClass(Class<?> type) {
        var serializer = cCache.get(type);

        if (serializer == null) synchronized (cCache) {
            serializer = cCache.get(type);
            if (serializer == null) {
                serializer = new SerializerMaker(type, null).make().finish();
                cCache.put(type, serializer);
            }
        }

        return serializer;
    }

    /**
     * Is called by generated code.
     */
    public static Serializer adapt(Serializer s, Class<?> type, Object descriptor) {
        if (s.descriptor().equals(descriptor)) {
            return s;
        }

        var descString = ((String) descriptor).intern();
        var key = new WithDescriptor(type, descString);

        var serializer = cCache.get(key);

        if (serializer == null) synchronized (cCache) {
            serializer = cCache.get(key);

            if (serializer == null) {
                var maker = new SerializerMaker(type, descString).make();

                var canonicalKey = new WithDescriptor(type, maker.mEffectiveDescriptor);
                serializer = cCache.get(canonicalKey);

                if (serializer != null) {
                    cCache.put(key, serializer);
                } else {
                    serializer = maker.finish();
                    cCache.put(key, serializer);
                    cCache.put(canonicalKey, serializer);
                }
            }
        }

        return serializer;
    }

    private record WithDescriptor(Class<?> type, String descriptor) { }

    private final Class<?> mType;
    private final String mDescriptor;

    private Map<String, String> mDescriptorMap;
    private ClassMaker mMaker;
    private MethodMaker mWriteMaker;
    private Variable mWritePipeVar, mWriteObjectVar;
    private MethodMaker mReadMaker;
    private Variable mReadPipeVar;
    private Variable mSkipPipeVar;
    private StringBuilder mDescriptorBuilder;
    private String mEffectiveDescriptor;

    private SerializerMaker(Class<?> type, String descriptor) {
        mType = type;
        mDescriptor = descriptor;
    }

    private SerializerMaker make() {
        if (!Modifier.isPublic(mType.getModifiers())) {
            throw new IllegalArgumentException("Not public: " + mType.getName());
        }

        if (mType.isRecord()) {
            makeForRecord();
        } else if (mType.isEnum()) {
            makeForEnum();
        } else {
            makeForClass();
        }

        mEffectiveDescriptor = mDescriptorBuilder.toString().intern();

        return this;
    }

    private Serializer finish() {
        mMaker.addMethod(Object.class, "descriptor").public_().return_(mEffectiveDescriptor);

        MethodMaker mm = mMaker.addMethod(Serializer.class, "adapt", Object.class).public_();
        mm.return_(mm.var(SerializerMaker.class).invoke("adapt", mm.this_(), mType, mm.param(0)));

        MethodHandles.Lookup lookup = mMaker.finishLookup();

        try {
            return (Serializer) lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class)).invoke();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private void makeForRecord() {
        RecordComponent[] components = mType.getRecordComponents();

        Class<?>[] paramTypes =
            Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);

        Constructor<?> ctor;
        try {
            ctor = mType.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        }

        if (!Modifier.isPublic(ctor.getModifiers())) {
            throw new IllegalArgumentException("No public constructor: " + mType.getName());
        }

        for (RecordComponent comp : components) {
            if (!Modifier.isPublic(comp.getAccessor().getModifiers())) {
                throw new IllegalArgumentException
                    ("Not public: " + mType.getName() + "." + comp.getName());
            }
        }

        // For consistency, write/read the components in sorted order.
        var componentMap = new TreeMap<RecordComponent, Integer>
            (Comparator.comparing(RecordComponent::getName));
        for (int i=0; i<components.length; i++) {
            componentMap.put(components[i], i);
        }

        begin();

        mDescriptorMap = parseDescriptorMap(mDescriptor);

        var readParamVars = new Variable[components.length];

        for (Map.Entry<RecordComponent, Integer> e : componentMap.entrySet()) {
            RecordComponent comp = e.getKey();
            int i = e.getValue();
            readParamVars[i] = mReadMaker.var(paramTypes[i]);

            if (shouldSkip(comp)) {
                readParamVars[i].clear();
            } else {
                String name = comp.getName();
                CoreUtils.writeParam(mWritePipeVar, mWriteObjectVar.invoke(name));
                CoreUtils.readParam(mReadPipeVar, readParamVars[i]);
                CoreUtils.skipParam(mSkipPipeVar, paramTypes[i]);
                appendToDescriptor(name, comp.getType());
            }
        }

        mReadMaker.return_(mReadMaker.new_(mType, (Object[]) readParamVars));
    }

    private void makeForClass() {
        try {
            mType.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No public no-arg constructor: " + mType.getName());
        }

        // For consistency, write/read the fields in sorted order.
        Field[] fields = mType.getFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));

        // Skip static and transient fields.
        for (int i=0; i<fields.length; i++) {
            Field field = fields[i];
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
                fields[i] = null;
            }
        }

        begin();

        mDescriptorMap = parseDescriptorMap(mDescriptor);

        var readObjectVar = mReadMaker.new_(mType);

        for (int i=0; i<fields.length; i++) {
            Field field = fields[i];
            if (field != null && !shouldSkip(field)) {
                String name = field.getName();
                CoreUtils.writeParam(mWritePipeVar, mWriteObjectVar.field(name));
                CoreUtils.readParam(mReadPipeVar, readObjectVar.field(name));
                CoreUtils.skipParam(mSkipPipeVar, field.getType());
                appendToDescriptor(name, field.getType());
            }
        }

        // With respect to memory ordering, treat the field stores as if they were final.
        mReadMaker.var(VarHandle.class).invoke("storeStoreFence");

        mReadMaker.return_(readObjectVar);
    }

    private void makeForEnum() {
        var enums = (Enum[]) mType.getEnumConstants();

        if (mDescriptor != null) {
            // Support a reduced set of enums.

            var supported = new LinkedHashMap<String, Enum>(enums.length * 2);
            for (Enum e : enums) {
                supported.put(e.name(), e);
            }

            String[] split = mDescriptor.split(";");
            var retain = new HashSet<String>(split.length * 2);
            Collections.addAll(retain, split);

            supported.keySet().retainAll(retain);

            enums = supported.values().toArray(Enum[]::new);
        }

        begin();

        var writeCases = new int[enums.length];
        var writeLabels = new Label[enums.length];

        for (int i=0; i<enums.length; i++) {
            writeCases[i] = enums[i].ordinal();
            writeLabels[i] = mWriteMaker.label();
        }

        // For consistency, number the enum constants in sorted order.
        var sortedEnums = enums.clone();
        Comparator<Enum> cmp = Comparator.comparing(Enum::name);
        Arrays.sort(sortedEnums, cmp);

        var readCases = new int[sortedEnums.length];
        var readLabels = new Label[sortedEnums.length];

        for (int i=0; i<sortedEnums.length; i++) {
            Enum e = sortedEnums[i];

            readCases[i] = i + 1;
            readLabels[i] = mReadMaker.label();

            if (!mDescriptorBuilder.isEmpty()) {
                mDescriptorBuilder.append(';');
            }
            mDescriptorBuilder.append(e.name());
        }

        // Make the write method.
        {
            Label unknown = mWriteMaker.label();
            mWriteObjectVar.invoke("ordinal").switch_(unknown, writeCases, writeLabels);

            var numberVar = mWriteMaker.var(int.class);
            Label doWrite = mWriteMaker.label();

            for (int i=0; i<enums.length; i++) {
                writeLabels[i].here();
                numberVar.set(Arrays.binarySearch(sortedEnums, enums[i], cmp) + 1);
                doWrite.goto_();
            }

            unknown.here();
            numberVar.set(0);

            doWrite.here();

            CoreUtils.writeIntId(mWritePipeVar, enums.length, numberVar);
        }

        // Make the read and skip methods.
        {
            var numberVar = CoreUtils.readIntId(mReadPipeVar, enums.length);
            CoreUtils.skipIntId(mSkipPipeVar, enums.length);

            Label end = mReadMaker.label();
            numberVar.switch_(end, readCases, readLabels);

            var typeVar = mReadMaker.var(mType);

            for (int i=0; i<sortedEnums.length; i++) {
                readLabels[i].here();
                mReadMaker.return_(typeVar.field(sortedEnums[i].name()));
            }

            end.here();
            mReadMaker.return_(null);
        }
    }

    private void begin() {
        String name = mType.getName();
        if (name.startsWith("java.")) {
            name = '$' + name;
        }

        ClassMaker cm = ClassMaker.begin(name, mType.getClassLoader(), CoreUtils.MAKER_KEY)
            .implement(Serializer.class);

        // Provide access to the adapt method, which is in a non-exported package.
        CoreUtils.allowAccess(cm);

        cm.addConstructor();

        MethodMaker mm = cm.addMethod(Set.class, "supportedTypes").public_();
        mm.return_(mm.var(Set.class).invoke("of", mType));

        mMaker = cm;

        mWriteMaker = mMaker.addMethod(null, "write", Pipe.class, Object.class).public_();
        mWritePipeVar = mWriteMaker.param(0);
        mWriteObjectVar = mWriteMaker.param(1).cast(mType);

        mReadMaker = mMaker.addMethod(Object.class, "read", Pipe.class).public_();
        mReadPipeVar = mReadMaker.param(0);

        mSkipPipeVar = mMaker.addMethod(null, "skip", Pipe.class).public_().param(0);

        mDescriptorBuilder = new StringBuilder();
    }

    /**
     * Returns true if the component isn't in the adapt descriptor.
     */
    private boolean shouldSkip(RecordComponent comp) {
        Map<String, String> descMap = mDescriptorMap;        
        if (descMap != null) {
            String desc = descMap.get(comp.getName());
            return desc == null || !desc.equals(comp.getType().descriptorString());
        }
        return false;
    }

    /**
     * Returns true if the field isn't in the adapt descriptor.
     */
    private boolean shouldSkip(Field field) {
        Map<String, String> descMap = mDescriptorMap;        
        if (descMap != null) {
            String desc = descMap.get(field.getName());
            return desc == null || !desc.equals(field.getType().descriptorString());
        }
        return false;
    }

    private void appendToDescriptor(String name, Class<?> type) {
        mDescriptorBuilder.append(name).append(';').append(type.descriptorString());
    }

    /**
     * Returns a field name to descriptor mapping, or null if the descriptor is null.
     */
    private static Map<String, String> parseDescriptorMap(String descriptor) {
        if (descriptor == null) {
            return null;
        }

        var map = new HashMap<String, String>();

        for (int ix = 0;;) {
            int ix2 = descriptor.indexOf(';', ix);
            if (ix2 < 0) {
                break;
            }

            String name = descriptor.substring(ix, ix2);
            ix = ix2 + 1;

            ix2 = ix;
            int c;
            while ((c = descriptor.charAt(ix2)) == '[') ix2++;

            if (c == 'L') {
                ix2 = descriptor.indexOf(';', ix2 + 1) + 1;
            } else {
                ix2++;
            }

            String desc = descriptor.substring(ix, ix2);

            map.put(name, desc);

            ix = ix2;
        }

        return map;
    }
}
