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

import java.util.List;
import java.util.Map;
import java.util.Set;

import cojen.classfile.ClassFile;
import cojen.classfile.CodeBuilder;
import cojen.classfile.Label;
import cojen.classfile.LocalVariable;
import cojen.classfile.MethodInfo;
import cojen.classfile.Modifiers;
import cojen.classfile.TypeDesc;

import cojen.util.ClassInjector;
import cojen.util.SoftValuedHashMap;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;
import dirmi.info.RemoteMethod;
import dirmi.info.RemoteParameter;

import dirmi.io.Connection;
import dirmi.io.RemoteInput;
import dirmi.io.RemoteOutput;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SkeletonFactoryGenerator<R extends Remote> {
    private static final String REMOTE_FIELD_NAME = "remote";
    private static final String SKELETON_SUPPORT_FIELD_NAME = "support";

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
                (mType, skeletonClass.getConstructor(mType, SkeletonSupport.class));
        } catch (NoSuchMethodException e) {
            NoSuchMethodError nsme = new NoSuchMethodError();
            nsme.initCause(e);
            throw nsme;
        }
    }

    private Class<? extends Skeleton> generateSkeleton() {
        ClassInjector ci =
            ClassInjector.create(mType.getName() + "$Skeleton", mType.getClassLoader());

        ClassFile cf = new ClassFile(ci.getClassName());
        cf.addInterface(Skeleton.class);
        cf.setSourceFile(SkeletonFactoryGenerator.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        final TypeDesc remoteType = TypeDesc.forClass(mType);
        final TypeDesc skeletonSupportType = TypeDesc.forClass(SkeletonSupport.class);
        final TypeDesc connectionType = TypeDesc.forClass(Connection.class);
        final TypeDesc remoteInType = TypeDesc.forClass(RemoteInput.class);
        final TypeDesc remoteOutType = TypeDesc.forClass(RemoteOutput.class);
        final TypeDesc noSuchMethodExType = TypeDesc.forClass(NoSuchMethodException.class);

        // Add fields
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true), REMOTE_FIELD_NAME, remoteType);
            cf.addField(Modifiers.PRIVATE.toFinal(true),
                        SKELETON_SUPPORT_FIELD_NAME, skeletonSupportType);
        }

        // Add constructor
        {
            MethodInfo mi = cf.addConstructor
                (Modifiers.PUBLIC, new TypeDesc[] {remoteType, skeletonSupportType});

            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.invokeSuperConstructor(null);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            b.storeField(REMOTE_FIELD_NAME, remoteType);
            b.loadThis();
            b.loadLocal(b.getParameter(1));
            b.storeField(SKELETON_SUPPORT_FIELD_NAME, skeletonSupportType);

            b.returnVoid();
        }

        // Add the all-important invoke method
        MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "invoke", null,
                                     new TypeDesc[] {TypeDesc.SHORT, connectionType});
        CodeBuilder b = new CodeBuilder(mi);

        Set<? extends RemoteMethod> methods = mInfo.getRemoteMethods();
        int caseCount = methods.size();
        int[] cases = new int[caseCount];
        Label[] switchLabels = new Label[caseCount];
        Label defaultLabel = b.createLabel();

        int i = 0;
        for (RemoteMethod method : methods) {
            cases[i] = method.getMethodID();
            switchLabels[i] = b.createLabel();
            i++;
        }

        LocalVariable methodIDVar = b.getParameter(0);
        LocalVariable conVar = b.getParameter(1);

        LocalVariable skeletonSupportVar = b.createLocalVariable(null, skeletonSupportType);
        b.loadThis();
        b.loadField(SKELETON_SUPPORT_FIELD_NAME, skeletonSupportType);
        b.storeLocal(skeletonSupportVar);

        // Each case operates on the remote server first, so put it on the stack early.
        b.loadThis();
        b.loadField(REMOTE_FIELD_NAME, remoteType);

        b.loadLocal(methodIDVar);
        b.switchBranch(cases, switchLabels, defaultLabel);

        // By default, throw a NoSuchMethodException.
        defaultLabel.setLocation();
        b.pop(); // pop remote server
        b.newObject(noSuchMethodExType);
        b.dup();
        b.loadLocal(methodIDVar);
        b.invokeStatic(TypeDesc.STRING, "valueOf", TypeDesc.STRING, new TypeDesc[] {TypeDesc.INT});
        b.invokeConstructor(noSuchMethodExType, new TypeDesc[] {TypeDesc.STRING});
        b.throwObject();

        // Now generate case for each method.

        Label[] tryStarts = new Label[caseCount];
        Label[] tryEnds = new Label[caseCount];
        boolean[] isAsync = new boolean[caseCount];
        int asyncCount = 0;

        i = 0;
        for (RemoteMethod method : methods) {
            switchLabels[i].setLocation();

            if (method.isAsynchronous()) {
                isAsync[i] = true;
                asyncCount++;
            }

            List<? extends RemoteParameter> paramTypes = method.getParameterTypes();

            if (paramTypes.size() != 0) {
                // Read parameters onto stack.
                b.loadLocal(skeletonSupportVar);
                b.loadLocal(conVar);
                b.invokeInterface(skeletonSupportType, "createRemoteInput",
                                  remoteInType, new TypeDesc[] {connectionType});
                LocalVariable remoteInVar = b.createLocalVariable(null, remoteInType);
                b.storeLocal(remoteInVar);

                for (RemoteParameter paramType : paramTypes) {
                    readParam(b, paramType, skeletonSupportVar, remoteInVar);
                }
            }

            TypeDesc returnTypeDesc = getTypeDesc(method.getReturnType());

            {
                tryStarts[i] = b.createLabel().setLocation();
                TypeDesc[] params = new TypeDesc[paramTypes.size()];
                for (int j=0; j<params.length; j++) {
                    params[j] = getTypeDesc(paramTypes.get(j));
                }

                b.invokeInterface(remoteType, method.getName(), returnTypeDesc, params);
                tryEnds[i] = b.createLabel().setLocation();
            }

            // Write response and close connection.

            if (method.isAsynchronous()) {
                if (returnTypeDesc != null) {
                    if (returnTypeDesc.isDoubleWord()) {
                        b.pop2();
                    } else {
                        b.pop();
                    }
                }
                // Assume caller has closed connection.
            } else {
                b.loadLocal(skeletonSupportVar);
                b.loadLocal(conVar);
                b.invokeInterface(skeletonSupportType, "createRemoteOutput",
                                  remoteOutType, new TypeDesc[] {connectionType});
                LocalVariable remoteOutVar = b.createLocalVariable(null, remoteOutType);
                b.storeLocal(remoteOutVar);

                if (returnTypeDesc == TypeDesc.BOOLEAN) {
                    b.loadLocal(remoteOutVar);
                    b.swap();
                    b.invokeInterface(remoteOutType, "writeOk", null,
                                      new TypeDesc[] {TypeDesc.BOOLEAN});
                } else {
                    if (returnTypeDesc != null) {
                        writeParam(b, method.getReturnType(), skeletonSupportVar, remoteOutVar);
                    }
                    b.loadLocal(remoteOutVar);
                    b.invokeInterface(remoteOutType, "writeOk", null, null);
                }

                b.loadLocal(conVar);
                b.invokeInterface(connectionType, "close", null, null);
            }

            b.returnVoid();

            i++;
        }

        // Create common exception handlers. One for regular methods, the other
        // for asynchronous methods.

        LocalVariable throwableVar =
            b.createLocalVariable(null, TypeDesc.forClass(Throwable.class));

        // Handler for asynchronous methods (if any). Re-throw exception
        // wrapped in AsynchronousInvocationException.
        if (asyncCount > 0) {
            for (i=0; i<caseCount; i++) {
                if (!isAsync[i]) {
                    continue;
                }
                b.exceptionHandler(tryStarts[i], tryEnds[i], Throwable.class.getName());
            }

            b.storeLocal(throwableVar);

            TypeDesc asyncExType = TypeDesc.forClass(AsynchronousInvocationException.class);
            b.newObject(asyncExType);
            b.dup();
            b.loadLocal(throwableVar);
            b.invokeConstructor(asyncExType, new TypeDesc[] {throwableVar.getType()});
            b.throwObject();
        }

        // Handler for synchronous methods (if any). Write exception to connection.
        if (caseCount - asyncCount > 0) {
            for (i=0; i<caseCount; i++) {
                if (isAsync[i]) {
                    continue;
                }
                b.exceptionHandler(tryStarts[i], tryEnds[i], Throwable.class.getName());
            }

            b.storeLocal(throwableVar);

            b.loadLocal(skeletonSupportVar);
            b.loadLocal(conVar);
            b.invokeInterface(skeletonSupportType, "createRemoteOutput",
                              remoteOutType, new TypeDesc[] {connectionType});
            b.loadLocal(throwableVar);
            b.invokeInterface(remoteOutType, "writeThrowable",
                              null, new TypeDesc[] {throwableVar.getType()});
            b.loadLocal(conVar);
            b.invokeInterface(connectionType, "close", null, null);
            
            b.returnVoid();
        }

        return ci.defineClass(cf);
    }

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
    private void readParam(CodeBuilder b,
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

    private void readRemoteParam(CodeBuilder b,
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
    private void writeParam(CodeBuilder b,
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

    private void writeRemoteParam(CodeBuilder b,
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

    private TypeDesc getTypeDesc(RemoteParameter param) {
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
