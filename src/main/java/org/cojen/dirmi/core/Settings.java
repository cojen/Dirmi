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

import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

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
    LinkedHashMap<Class<?>, Serializer<?>> serializers;

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

    Settings withSerializers(LinkedHashMap<Class<?>, Serializer<?>> serializers) {
        var settings = copy();
        settings.serializers = serializers;
        return settings;
    }

    /**
     * Writes the first custom type code and then the class names. If aren't any serializers,
     * then only 0 is written.
     *
     * @return a new set with the types that were written, or null if none
     */
    LinkedHashSet<String> writeSerializerTypes(Pipe pipe) throws IOException {
        if (serializers == null || serializers.isEmpty()) {
            pipe.writeByte(0);
            return null;
        }

        int size = serializers.size();
        var types = new LinkedHashSet<String>(size);
            
        pipe.writeByte(TypeCodes.T_FIRST_CUSTOM);
        pipe.writeInt(size);

        for (Class<?> clazz : serializers.keySet()) {
            String name = clazz.getName();
            types.add(name);
            pipe.writeObject(name);
        }

        return types;
    }

    /**
     * Caller must have already read the first custom type code. If 0, then there aren't any
     * serializer types to read.
     */
    static LinkedHashSet<String> readSerializerTypes(Pipe pipe) throws IOException {
        int size = pipe.readInt();
        var set = new LinkedHashSet<String>(size);
        for (int i=0; i<size; i++) {
            set.add((String) pipe.readObject());
        }
        return set;
    }

    /**
     * @param typeCode the first custom type code to assign, as suggested by the remote side
     * @param serverCustomTypes can be null
     * @param clientCustomTypes can be null
     */
    TypeCodeMap mergeSerializerTypes(int typeCode,
                                     LinkedHashSet<String> serverCustomTypes,
                                     LinkedHashSet<String> clientCustomTypes)
    {
        if (isEmpty(serverCustomTypes) && isEmpty(clientCustomTypes)) {
            return TypeCodeMap.STANDARD;
        }

        var available = new HashMap<String, ClassSerializer>();

        for (Map.Entry<Class<?>, Serializer<?>> e : serializers.entrySet()) {
            Class clazz = e.getKey();
            String name = clazz.getName();

            Serializer serializer;
            if (exists(serverCustomTypes, name) && exists(clientCustomTypes, name)) {
                serializer = e.getValue();
            } else {
                serializer = null;
            }

            available.put(name, new ClassSerializer(clazz, serializer));
        }

        var merged = new LinkedHashMap<Object, Serializer>();
        putAll(serverCustomTypes, merged, available);
        putAll(clientCustomTypes, merged, available);

        typeCode = Math.max(typeCode, TypeCodes.T_FIRST_CUSTOM);

        return TypeCodeMap.find(typeCode, merged);
    }

    private static boolean isEmpty(LinkedHashSet<?> set) {
        return set == null || set.isEmpty();
    }

    private static boolean exists(LinkedHashSet<?> set, Object key) {
        return set != null && set.contains(key);
    }

    private static void putAll(LinkedHashSet<String> src, LinkedHashMap<Object, Serializer> dst,
                               Map<String, ClassSerializer> available)
    {
        for (String name : src) {
            if (available == null || !available.containsKey(name)) {
                dst.put(name, null);
            } else {
                ClassSerializer cs = available.get(name);
                dst.put(cs.mClass, cs.mSerializer);
            }
        }
    }

    private static class ClassSerializer {
        final Class mClass;
        final Serializer mSerializer;

        ClassSerializer(Class clazz, Serializer serializer) {
            mClass = clazz;
            mSerializer = serializer;
        }
    }
}
