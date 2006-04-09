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

import java.rmi.Remote;

import java.util.Collection;

import cojen.classfile.CodeBuilder;
import cojen.classfile.LocalVariable;
import cojen.classfile.TypeDesc;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteParameter;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class CodeBuilderUtil {
    /**
     * Generates code to read a parameter from a RemoteInput, leaving it on the
     * stack. RemoteSupport is used for processing Remote parameters. Generated
     * code may throw an IOException, NoSuchObjectException, or
     * ClassNotFoundException.
     *
     * @param paramType type of parameter to read
     * @param remoteSupportVar variable which references a RemoteSupport instance
     * @param remoteInVar variable which references a RemoteInput instance
     */
    static void readParam(CodeBuilder b,
                          RemoteParameter paramType,
                          LocalVariable remoteSupportVar,
                          LocalVariable remoteInVar)
    {
        if (paramType.isRemote()) {
            readRemoteParam(b, paramType.getRemoteDimensions(), paramType.getRemoteInfoType(),
                            remoteSupportVar, remoteInVar);
            return;
        }

        TypeDesc type = TypeDesc.forClass(paramType.getSerializedType());

        if (type.isPrimitive()) {
            String typeName = type.getFullName();
            typeName = Character.toUpperCase(typeName.charAt(0)) + typeName.substring(1);
            String methodName = "read" + typeName;
            b.loadLocal(remoteInVar);
            b.invokeInterface(remoteInVar.getType(), methodName, type, null);
            return;
        }

        // Read ordinary serialized object.
        b.loadLocal(remoteInVar);
        b.invokeInterface(remoteInVar.getType(), "readObject", TypeDesc.OBJECT, null);
        b.checkCast(type);
    }

    private static void readRemoteParam(CodeBuilder b,
                                        int dimensions, RemoteInfo info,
                                        LocalVariable remoteSupportVar,
                                        LocalVariable remoteInVar)
    {
        if (dimensions <= 0) {
            b.loadLocal(remoteSupportVar);
            b.loadLocal(remoteInVar);
            b.invokeInterface(remoteInVar.getType(), "readInt", TypeDesc.INT, null);
            b.invokeInterface(remoteSupportVar.getType(), "getObject",
                              TypeDesc.forClass(Remote.class),
                              new TypeDesc[] {TypeDesc.INT});
            b.checkCast(TypeDesc.forClass(info.getName()));
            return;
        }

        /* TODO: support arrays of remotes using regular object serialization?
        b.loadLocal(remoteInVar);
        b.invokeInterface(remoteInVar.getType(), "readLength", TypeDesc.INT, null);

        do {
            if (dimensions > 0) {
            }
            
            dimensions--;
        } while (dimensions > 0);
        */
    }

    /**
     * Generates code to write a parameter to a RemoteOutput.  RemoteSupport is
     * used for processing Remote parameters. Generated code may throw an
     * IOException.
     *
     * @param paramType type of parameter to write
     * @param remoteSupportVar variable which references a RemoteSupport instance
     * @param remoteOutVar variable which references a RemoteOutput instance
     */
    static void writeParam(CodeBuilder b,
                           RemoteParameter paramType,
                           LocalVariable remoteSupportVar,
                           LocalVariable remoteOutVar)
    {
        if (paramType.isRemote()) {
            writeRemoteParam(b, paramType.getRemoteDimensions(), paramType.getRemoteInfoType(),
                             remoteSupportVar, remoteOutVar);
            return;
        }

        TypeDesc type = TypeDesc.forClass(paramType.getSerializedType());

        if (type.isPrimitive()) {
            b.loadLocal(remoteOutVar);
            b.swap();
            b.invokeInterface(remoteOutVar.getType(), "write", null, new TypeDesc[] {type});
            return;
        }

        // Write ordinary serialized object.
        b.loadLocal(remoteOutVar);
        b.swap();
        b.invokeInterface(remoteOutVar.getType(), "write", null, new TypeDesc[] {TypeDesc.OBJECT});
    }

    private static void writeRemoteParam(CodeBuilder b,
                                         int dimensions, RemoteInfo info,
                                         LocalVariable remoteSupportVar,
                                         LocalVariable remoteOutVar)
    {
        if (dimensions <= 0) {
            LocalVariable remoteVar = b.createLocalVariable(null, TypeDesc.forClass(Remote.class));
            b.storeLocal(remoteVar);

            b.loadLocal(remoteOutVar);
            b.loadLocal(remoteSupportVar);
            b.loadLocal(remoteVar);
            b.invokeInterface(remoteSupportVar.getType(), "getObjectID",
                              TypeDesc.INT,
                              new TypeDesc[] {remoteVar.getType()});
            b.invokeInterface(remoteOutVar.getType(), "write",
                              null, new TypeDesc[] {TypeDesc.INT});
            return;
        }

        // TODO: support arrays of remotes using regular object serialization?
    }

    static TypeDesc getTypeDesc(RemoteParameter param) {
        if (param == null) {
            return null;
        }
        if (!param.isRemote()) {
            return TypeDesc.forClass(param.getSerializedType());
        } 
        TypeDesc type = TypeDesc.forClass(param.getRemoteInfoType().getName());
        int dimensions = param.getRemoteDimensions();
        while (--dimensions >= 0) {
            type = type.toArrayType();
        }
        return type;
    }

    static TypeDesc[] getTypeDescs(Collection<? extends RemoteParameter> params) {
        TypeDesc[] paramDescs = new TypeDesc[params.size()];
        int j = 0;
        for (RemoteParameter param : params) {
            paramDescs[j++] = getTypeDesc(param);
        }
        return paramDescs;
    }
}
