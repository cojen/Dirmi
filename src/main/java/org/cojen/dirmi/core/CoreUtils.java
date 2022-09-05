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

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Remote;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class CoreUtils {
    static final Object MAKER_KEY = new Object();

    /**
     * Grants the given maker access to the core package.
     */
    static void allowAccess(ClassMaker cm) {
        var thisModule = CoreUtils.class.getModule();
        var thatModule = cm.classLoader().getUnnamedModule();
        thisModule.addExports("org.cojen.dirmi.core", thatModule);
    }

    static boolean isRemote(Class<?> clazz) {
        return Remote.class.isAssignableFrom(clazz) ||
            // Be lenient and also support java.rmi interfaces.
            java.rmi.Remote.class.isAssignableFrom(clazz);
    }

    static boolean isUnchecked(Class<? extends Throwable> clazz) {
        return RuntimeException.class.isAssignableFrom(clazz)
            || Error.class.isAssignableFrom(clazz);
    }

    static void writeParam(Variable pipeVar, Variable paramVar) {
        Class<?> type = paramVar.classType();

        if (!type.isPrimitive()) {
            pipeVar.invoke("writeObject", paramVar);
        } else {
            String m;
            if (type == int.class) {
                m = "writeInt";
            } else if (type == long.class) {
                m = "writeLong";
            } else if (type == boolean.class) {
                m = "writeBoolean";
            } else if (type == double.class) {
                m = "writeDouble";
            } else if (type == float.class) {
                m = "writeFloat";
            } else if (type == byte.class) {
                m = "writeByte";
            } else if (type == char.class) {
                m = "writeChar";
            } else if (type == short.class) {
                m = "writeShort";
            } else {
                throw new AssertionError();
            }
            pipeVar.invoke(m, paramVar);
        }
    }

    static void readParam(Variable pipeVar, Variable paramVar) {
        Class<?> type = paramVar.classType();

        if (!type.isPrimitive()) {
            var objectVar = pipeVar.invoke("readObject");
            paramVar.set(type == Object.class ? objectVar : objectVar.cast(type));
        } else {
            String m;
            if (type == int.class) {
                m = "readInt";
            } else if (type == long.class) {
                m = "readLong";
            } else if (type == boolean.class) {
                m = "readBoolean";
            } else if (type == double.class) {
                m = "readDouble";
            } else if (type == float.class) {
                m = "readFloat";
            } else if (type == byte.class) {
                m = "readByte";
            } else if (type == char.class) {
                m = "readChar";
            } else if (type == short.class) {
                m = "readShort";
            } else {
                throw new AssertionError();
            }
            paramVar.set(pipeVar.invoke(m));
        }
    }
}
