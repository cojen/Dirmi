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

import java.io.IOException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cojen.dirmi.ClassResolver;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Serializer;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class Settings implements Cloneable {
    int reconnectDelayMillis;
    int pingTimeoutMillis;
    int idleConnectionMillis;
    ClassResolver resolver;
    LinkedHashMap<Class<?>, Serializer> serializers;

    Settings() {
        reconnectDelayMillis =  1_000;
        pingTimeoutMillis    =  2_000;
        idleConnectionMillis = 60_000;
    }

    Settings copy() {
        try {
            return (Settings) clone();
        } catch (CloneNotSupportedException e) {
            throw CoreUtils.rethrow(e);
        }
    }

    Settings withReconnectDelayMillis(int millis) {
        var settings = copy();
        settings.reconnectDelayMillis = millis;
        return settings;
    }

    Settings withPingTimeoutMillis(int millis) {
        var settings = copy();
        settings.pingTimeoutMillis = millis;
        return settings;
    }

    Settings withIdleConnectionMillis(int millis) {
        var settings = copy();
        settings.idleConnectionMillis = millis;
        return settings;
    }

    Settings withClassResolver(ClassResolver resolver) {
        var settings = copy();
        settings.resolver = resolver;
        return settings;
    }

    Settings withSerializers(LinkedHashMap<Class<?>, Serializer> serializers) {
        var settings = copy();
        settings.serializers = serializers;
        return settings;
    }

    /**
     * Writes the first custom type code and then the class names. If aren't any serializers,
     * then only 0 is written.
     *
     * @return a new map with the types and descriptors that were written, or null if none
     */
    LinkedHashMap<String, Object> writeSerializerTypes(Pipe pipe) throws IOException {
        if (serializers == null || serializers.isEmpty()) {
            pipe.writeByte(0);
            return null;
        }

        int size = serializers.size();
        var typeMap = new LinkedHashMap<String, Object>(size * 2);
            
        pipe.writeByte(TypeCodes.T_FIRST_CUSTOM);
        pipe.writeInt(size);

        for (Map.Entry<Class<?>, Serializer> e : serializers.entrySet()) {
            String name = e.getKey().getName();
            Object descriptor = e.getValue().descriptor();
            typeMap.put(name, descriptor);
            pipe.writeObject(name);
            pipe.writeObject(descriptor);
        }

        return typeMap;
    }

    /**
     * Caller must have already read the first custom type code. If 0, then there aren't any
     * serializer types to read.
     */
    static LinkedHashMap<String, Object> readSerializerTypes(Pipe pipe) throws IOException {
        int size = pipe.readInt();
        var typeMap = new LinkedHashMap<String, Object>(size * 2);
        for (int i=0; i<size; i++) {
            var name = (String) pipe.readObject();
            var descriptor = pipe.readObject();
            typeMap.put(name, descriptor);
        }
        return typeMap;
    }

    /**
     * @param typeCode the first custom type code to assign, as suggested by the remote side
     * @param localCustomTypes can be null
     * @param remoteCustomTypes can be null
     */
    TypeCodeMap mergeSerializerTypes(int typeCode,
                                     LinkedHashMap<String, Object> localCustomTypes,
                                     LinkedHashMap<String, Object> remoteCustomTypes)
    {
        if (isEmpty(localCustomTypes) && isEmpty(remoteCustomTypes)) {
            return TypeCodeMap.STANDARD;
        }

        var available = new HashMap<String, ClassSerializer>();

        for (Map.Entry<Class<?>, Serializer> e : serializers.entrySet()) {
            Class clazz = e.getKey();
            String name = clazz.getName();

            Serializer serializer;
            if (containsKey(localCustomTypes, name) && containsKey(remoteCustomTypes, name)) {
                serializer = e.getValue();
                serializer = serializer.adapt(remoteCustomTypes.get(name));
            } else {
                serializer = null;
            }

            available.put(name, new ClassSerializer(clazz, serializer));
        }

        var merged = new LinkedHashMap<Object, Serializer>();
        putAll(localCustomTypes, merged, available);
        putAll(remoteCustomTypes, merged, available);

        typeCode = Math.max(typeCode, TypeCodes.T_FIRST_CUSTOM);

        return TypeCodeMap.find(typeCode, merged);
    }

    private static boolean isEmpty(LinkedHashMap<?,?> map) {
        return map == null || map.isEmpty();
    }

    private static boolean containsKey(LinkedHashMap<?,?> map, Object key) {
        return map != null && map.containsKey(key);
    }

    private static void putAll(LinkedHashMap<String, Object> src,
                               LinkedHashMap<Object, Serializer> dst,
                               Map<String, ClassSerializer> available)
    {
        if (src != null) for (Map.Entry<String, Object> e : src.entrySet()) {
            String name = e.getKey();
            if (available == null || !available.containsKey(name)) {
                dst.put(name, null);
            } else {
                ClassSerializer cs = available.get(name);
                dst.put(cs.clazz, cs.serializer);
            }
        }
    }

    private record ClassSerializer(Class clazz, Serializer serializer) { }
}
