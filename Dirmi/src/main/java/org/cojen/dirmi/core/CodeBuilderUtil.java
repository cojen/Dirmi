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

package org.cojen.dirmi.core;

import java.lang.reflect.InvocationTargetException;

import java.io.DataOutput;

import java.rmi.Remote;

import java.rmi.server.Unreferenced;

import java.util.Collection;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.cojen.classfile.ClassFile;
import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.Label;
import org.cojen.classfile.LocalVariable;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.TypeDesc;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.UnimplementedMethodException;

import org.cojen.dirmi.info.RemoteInfo;
import org.cojen.dirmi.info.RemoteMethod;
import org.cojen.dirmi.info.RemoteParameter;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class CodeBuilderUtil {
    static final String FACTORY_FIELD = "factory";

    // Method name ends with '$' so as not to conflict with user method.
    static final String FACTORY_REF_METHOD_NAME = "factoryRef$";

    static final TypeDesc IDENTIFIER_TYPE;
    static final TypeDesc VERSIONED_IDENTIFIER_TYPE;
    static final TypeDesc STUB_SUPPORT_TYPE;
    static final TypeDesc SKEL_SUPPORT_TYPE;
    static final TypeDesc INV_CHANNEL_TYPE;
    static final TypeDesc INV_IN_TYPE;
    static final TypeDesc INV_OUT_TYPE;
    static final TypeDesc NO_SUCH_METHOD_EX_TYPE;
    static final TypeDesc UNIMPLEMENTED_EX_TYPE;
    static final TypeDesc ASYNC_INV_EX_TYPE;
    static final TypeDesc BATCH_INV_EX_TYPE;
    static final TypeDesc THROWABLE_TYPE;
    static final TypeDesc CLASS_TYPE;
    static final TypeDesc FUTURE_TYPE;
    static final TypeDesc TIME_UNIT_TYPE;
    static final TypeDesc PIPE_TYPE;
    static final TypeDesc DATA_OUTPUT_TYPE;
    static final TypeDesc UNREFERENCED_TYPE;

    static {
        IDENTIFIER_TYPE = TypeDesc.forClass(Identifier.class);
        VERSIONED_IDENTIFIER_TYPE = TypeDesc.forClass(VersionedIdentifier.class);
        STUB_SUPPORT_TYPE = TypeDesc.forClass(StubSupport.class);
        SKEL_SUPPORT_TYPE = TypeDesc.forClass(SkeletonSupport.class);
        INV_CHANNEL_TYPE = TypeDesc.forClass(InvocationChannel.class);
        INV_IN_TYPE = TypeDesc.forClass(InvocationInputStream.class);
        INV_OUT_TYPE = TypeDesc.forClass(InvocationOutputStream.class);
        NO_SUCH_METHOD_EX_TYPE = TypeDesc.forClass(NoSuchMethodException.class);
        UNIMPLEMENTED_EX_TYPE = TypeDesc.forClass(UnimplementedMethodException.class);
        ASYNC_INV_EX_TYPE = TypeDesc.forClass(AsynchronousInvocationException.class);
        BATCH_INV_EX_TYPE = TypeDesc.forClass(BatchedInvocationException.class);
        THROWABLE_TYPE = TypeDesc.forClass(Throwable.class);
        CLASS_TYPE = TypeDesc.forClass(Class.class);
        FUTURE_TYPE = TypeDesc.forClass(Future.class);
        TIME_UNIT_TYPE = TypeDesc.forClass(TimeUnit.class);
        PIPE_TYPE = TypeDesc.forClass(Pipe.class);
        DATA_OUTPUT_TYPE = TypeDesc.forClass(DataOutput.class);
        UNREFERENCED_TYPE = TypeDesc.forClass(Unreferenced.class);
    }

    static boolean equalTypes(RemoteParameter a, RemoteParameter b) {
        return a == null ? b == null : (a.equalTypes(b));
    }

    static String cleanClassName(String name) {
        if (name.startsWith("java.")) {
            // Rename to avoid SecurityException.
            name = "java$" + name.substring(4);
        }
        return name;
    }

    /**
     * Generates code to read a parameter from an InvocationInput, cast it, and
     * leave it on the stack. Generated code may throw an IOException,
     * NoSuchObjectException, ClassNotFoundException, or ClassCastException.
     *
     * @param param type of parameter to read
     * @param invInVar variable which references an InvocationInput instance
     */
    static void readParam(CodeBuilder b,
                          RemoteParameter param,
                          LocalVariable invInVar)
    {
        TypeDesc type = getTypeDesc(param);

        if (type.isPrimitive() || !param.isUnshared()) {
            readValue(b, type, invInVar);
            return;
        }

        String methodName;
        TypeDesc methodType;
        TypeDesc castType;

        if (TypeDesc.STRING == type) {
            methodName = "readUnsharedString";
            methodType = type;
            castType = null;
        } else {
            methodName = "readUnshared";
            methodType = TypeDesc.OBJECT;
            castType = type;
        }

        b.loadLocal(invInVar);
        if (invInVar.getType().toClass().isInterface()) {
            b.invokeInterface(invInVar.getType(), methodName, methodType, null);
        } else {
            b.invokeVirtual(invInVar.getType(), methodName, methodType, null);
        }

        if (castType != null && castType != TypeDesc.OBJECT) {
            b.checkCast(type);
        }
    }

    /**
     * Generates code to read a value from an ObjectInput, cast it, and leave
     * it on the stack. Generated code may throw an IOException,
     * NoSuchObjectException, ClassNotFoundException, or ClassCastException.
     *
     * @param type type of parameter to read
     * @param inVar variable which references an ObjectInput instance
     */
    static void readValue(CodeBuilder b,
                          TypeDesc type,
                          LocalVariable inVar)
    {
        String methodName;
        TypeDesc methodType;
        TypeDesc castType;

        if (type.isPrimitive()) {
            methodName = type.getRootName();
            methodName = "read" +
                Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
            methodType = type;
            castType = null;
        } else {
            methodName = "readObject";
            methodType = TypeDesc.OBJECT;
            castType = type;
        }

        b.loadLocal(inVar);
        if (inVar.getType().toClass().isInterface()) {
            b.invokeInterface(inVar.getType(), methodName, methodType, null);
        } else {
            b.invokeVirtual(inVar.getType(), methodName, methodType, null);
        }

        if (castType != null && castType != TypeDesc.OBJECT) {
            b.checkCast(type);
        }
    }

    /**
     * Generates code to write a parameter to an InvocationOutput. Generated
     * code may throw an IOException.
     *
     * @param param type of parameter to write
     * @param invOutVar variable which references a InvocationOutput instance
     * @param paramVar variable which references parameter value
     * @return true if param was written as shared
     */
    static boolean writeParam(CodeBuilder b,
                              RemoteParameter param,
                              LocalVariable invOutVar,
                              LocalVariable paramVar)
    {
        TypeDesc type = getTypeDesc(param);

        if (type.isPrimitive() || !param.isUnshared()) {
            return writeValue(b, type, invOutVar, paramVar);
        }

        String methodName;
        TypeDesc methodType;

        if (TypeDesc.STRING == type) {
            methodName = "writeUnsharedString";
            methodType = type;
        } else {
            methodName = "writeUnshared";
            methodType = TypeDesc.OBJECT;
        }

        b.loadLocal(invOutVar);
        b.loadLocal(paramVar);
        if (invOutVar.getType().toClass().isInterface()) {
            b.invokeInterface(invOutVar.getType(), methodName, null, new TypeDesc[] {methodType});
        } else {
            b.invokeVirtual(invOutVar.getType(), methodName, null, new TypeDesc[] {methodType});
        }

        return false;
    }

    /**
     * Generates code to write a value to an ObjectOutput. Generated code may
     * throw an IOException.
     *
     * @param type type of parameter to write
     * @param outVar variable which references an ObjectOutput instance
     * @param valueVar variable which references value; pass null if on stack
     * @return true if value was written as shared
     */
    static boolean writeValue(CodeBuilder b,
                              TypeDesc type,
                              LocalVariable outVar,
                              LocalVariable valueVar)
    {
        boolean shared;
        String methodName;
        TypeDesc methodType;

        if (type.isPrimitive()) {
            shared = false;
            methodName = type.getRootName();
            methodName = "write" +
                Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
            switch (type.getTypeCode()) {
            case TypeDesc.BYTE_CODE: case TypeDesc.SHORT_CODE: case TypeDesc.CHAR_CODE:
                methodType = TypeDesc.INT;
                break;
            default:
                methodType = type;
                break;
            }
        } else {
            shared = true;
            methodName = "writeObject";
            methodType = TypeDesc.OBJECT;
        }

        b.loadLocal(outVar);
        if (valueVar == null) {
            b.swap();
        } else {
            b.loadLocal(valueVar);
        }
        if (outVar.getType().toClass().isInterface()) {
            b.invokeInterface(outVar.getType(), methodName, null, new TypeDesc[] {methodType});
        } else {
            b.invokeVirtual(outVar.getType(), methodName, null, new TypeDesc[] {methodType});
        }

        return shared;
    }

    static TypeDesc getTypeDesc(RemoteParameter param) {
        if (param == null) {
            return null;
        }
        return TypeDesc.forClass(param.getType());
    }

    static TypeDesc[] getTypeDescs(Collection<? extends RemoteParameter> params) {
        TypeDesc[] paramDescs = new TypeDesc[params.size()];
        int j = 0;
        for (RemoteParameter param : params) {
            paramDescs[j++] = getTypeDesc(param);
        }
        return paramDescs;
    }

    static void addFactoryRefMethod(ClassFile cf) {
        cf.addField(Modifiers.PRIVATE.toStatic(true).toVolatile(true),
                    FACTORY_FIELD, TypeDesc.OBJECT);

        MethodInfo mi = cf.addMethod
            (Modifiers.PUBLIC.toStatic(true), FACTORY_REF_METHOD_NAME,
             null, new TypeDesc[] {TypeDesc.OBJECT});

        CodeBuilder b = new CodeBuilder(mi);

        b.loadLocal(b.getParameter(0));
        b.storeStaticField(FACTORY_FIELD, TypeDesc.OBJECT);

        b.returnVoid();
    }

    /**
     * @param factory Strong reference is kept to this object. As long as stub
     * or skeleton instances exist, the factory will not get reclaimed.
     */
    static void invokeFactoryRefMethod(Class clazz, Object factory)
        throws NoSuchMethodException, IllegalAccessException, InvocationTargetException
    {
        clazz.getMethod(FACTORY_REF_METHOD_NAME, Object.class).invoke(null, factory);
    }
}
