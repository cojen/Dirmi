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

package dirmi.core;

import java.lang.reflect.InvocationTargetException;

import java.rmi.Remote;

import java.util.Collection;

import org.cojen.classfile.ClassFile;
import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.Label;
import org.cojen.classfile.LocalVariable;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.ClassInjector;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteMethod;
import dirmi.info.RemoteParameter;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class CodeBuilderUtil {
    static final String FACTORY_FIELD = "factory";

    static final String METHOD_ID_FIELD_PREFIX = "method_";

    // Method name ends with '$' so as not to conflict with user method.
    static final String INIT_METHOD_NAME = "init$";

    static ClassInjector createInjector(Class<?> type, String suffix) {
        String name = type.getName();
        if (name.startsWith("java.")) {
            // Rename to avoid SecurityException.
            name = "java$" + name.substring(4);
        }
        return ClassInjector.create(name + '$' + suffix, type.getClassLoader());
    }

    /**
     * Generates code to read a parameter from a RemoteInput, cast it, and
     * leave it on the stack. Generated code may throw an IOException,
     * NoSuchObjectException, or ClassNotFoundException.
     *
     * @param param type of parameter to read
     * @param remoteInVar variable which references a RemoteInput instance
     */
    static void readParam(CodeBuilder b,
                          RemoteParameter param,
                          LocalVariable remoteInVar)
    {
        TypeDesc type = getTypeDesc(param);

        String methodName;
        TypeDesc methodType;
        TypeDesc castType;

        if (type.isPrimitive()) {
            methodName = type.getRootName();
            methodName = "read" +
                Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
            methodType = type;
            castType = null;
        } else if (param.isUnshared()) {
            if (TypeDesc.STRING == type) {
                methodName = "readUnsharedString";
                methodType = type;
                castType = null;
            } else {
                methodName = "readUnshared";
                methodType = TypeDesc.OBJECT;
                castType = type;
            }
        } else {
            methodName = "readObject";
            methodType = TypeDesc.OBJECT;
            castType = type;
        }

        b.loadLocal(remoteInVar);
        b.invokeVirtual(remoteInVar.getType(), methodName, methodType, null);

        if (castType != null && castType != TypeDesc.OBJECT) {
            b.checkCast(type);
        }
    }

    /**
     * Generates code to write a parameter to a RemoteOutput. Generated code
     * may throw an IOException.
     *
     * @param param type of parameter to write
     * @param remoteOutVar variable which references a RemoteOutput instance
     * @param paramVar variable which references parameter value
     */
    static void writeParam(CodeBuilder b,
                           RemoteParameter param,
                           LocalVariable remoteOutVar,
                           LocalVariable paramVar)
    {
        TypeDesc type = getTypeDesc(param);

        String methodName;
        TypeDesc methodType;

        if (type.isPrimitive()) {
            methodName = type.getRootName();
            methodName = "write" +
                Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
            methodType = type;
        } else if (param.isUnshared()) {
            if (TypeDesc.STRING == type) {
                methodName = "writeUnsharedString";
                methodType = type;
            } else {
                methodName = "writeUnshared";
                methodType = TypeDesc.OBJECT;
            }
        } else {
            methodName = "writeObject";
            methodType = TypeDesc.OBJECT;
        }

        b.loadLocal(remoteOutVar);
        b.loadLocal(paramVar);
        b.invokeVirtual(remoteOutVar.getType(), methodName, null, new TypeDesc[] {methodType});
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

    static void loadMethodID(CodeBuilder b, int methodOrdinal) {
        final TypeDesc identifierType = TypeDesc.forClass(Identifier.class);
        b.loadStaticField(METHOD_ID_FIELD_PREFIX + methodOrdinal, identifierType);
    }

    static void addInitMethodAndFields(ClassFile cf, RemoteInfo info) {
        cf.addField(Modifiers.PRIVATE.toStatic(true), FACTORY_FIELD, TypeDesc.OBJECT);
        addMethodIDFields(cf, info);
        addInitMethod(cf, info);
    }

    private static void addMethodIDFields(ClassFile cf, RemoteInfo info) {
        final TypeDesc identifierType = TypeDesc.forClass(Identifier.class);
        int methodOrdinal = -1;
        for (RemoteMethod method : info.getRemoteMethods()) {
            methodOrdinal++;
            cf.addField(Modifiers.PRIVATE.toStatic(true),
                        METHOD_ID_FIELD_PREFIX + methodOrdinal, identifierType);
        }
    }

    private static void addInitMethod(ClassFile cf, RemoteInfo info) {
        final TypeDesc identifierType = TypeDesc.forClass(Identifier.class);
        final TypeDesc identifierArrayType = identifierType.toArrayType();

        MethodInfo mi = cf.addMethod
            (Modifiers.PUBLIC.toStatic(true).toSynchronized(true), INIT_METHOD_NAME,
             null, new TypeDesc[] {TypeDesc.OBJECT, identifierArrayType});

        CodeBuilder b = new CodeBuilder(mi);

        b.loadLocal(b.getParameter(0));
        b.storeStaticField(FACTORY_FIELD, TypeDesc.OBJECT);

        int methodOrdinal = -1;
        for (RemoteMethod method : info.getRemoteMethods()) {
            methodOrdinal++;

            if (methodOrdinal == 0) {
                // Crude check to ensure init is called at most once.
                b.loadStaticField(METHOD_ID_FIELD_PREFIX + methodOrdinal, identifierType);
                Label doInit = b.createLabel();
                b.ifNullBranch(doInit, true);

                b.newObject(TypeDesc.forClass(IllegalStateException.class));
                b.dup();
                b.invokeConstructor(TypeDesc.forClass(IllegalStateException.class), null);
                b.throwObject();

                doInit.setLocation();
            }

            b.loadLocal(b.getParameter(1));
            b.loadConstant(methodOrdinal);
            b.loadFromArray(identifierType);
            b.storeStaticField(METHOD_ID_FIELD_PREFIX + methodOrdinal, identifierType);
        }

        b.returnVoid();
    }

    /**
     * @param factory Strong reference is kept to this object. As long as stub
     * or skeleton instances exist, the factory will not get reclaimed.
     */
    static void invokeInitMethod(Class clazz, Object factory, RemoteInfo info)
        throws NoSuchMethodException, IllegalAccessException, InvocationTargetException
    {
        // Prepare identifiers for init method.
        Identifier[] ids = new Identifier[info.getRemoteMethods().size()];
        int methodOrdinal = -1;
        for (RemoteMethod method : info.getRemoteMethods()) {
            methodOrdinal++;
            ids[methodOrdinal] = method.getMethodID();
        }

        // Call static method to initialize method identifiers.
        clazz.getMethod(INIT_METHOD_NAME, Object.class, Identifier[].class)
            .invoke(null, factory, ids);
    }
}
