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

import java.io.DataOutput;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cojen.classfile.ClassFile;
import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.Label;
import org.cojen.classfile.LocalVariable;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.ClassInjector;
import org.cojen.util.KeyFactory;
import org.cojen.util.SoftValuedHashMap;

import dirmi.Pipe;
import dirmi.UnimplementedMethodException;

import dirmi.core.Identifier;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;
import dirmi.info.RemoteMethod;
import dirmi.info.RemoteParameter;

/**
 * Generates {@link StubFactory} instances for any given Remote type.
 *
 * @author Brian S O'Neill
 */
public class StubFactoryGenerator<R extends Remote> {
    private static final String STUB_SUPPORT_NAME = "support";

    private static final Map<Object, StubFactory<?>> cCache;

    static {
        cCache = new SoftValuedHashMap<Object, StubFactory<?>>();
    }

    /**
     * Returns a new or cached StubFactory.
     *
     * @param type
     * @param remoteInfo remote type as supported by remote server
     * @throws IllegalArgumentException if type is null or malformed
     */
    public static <R extends Remote> StubFactory<R> getStubFactory(Class<R> type,
                                                                   RemoteInfo remoteInfo)
        throws IllegalArgumentException
    {
        Object key = KeyFactory.createKey(new Object[] {type, remoteInfo});

        synchronized (cCache) {
            StubFactory<R> factory = (StubFactory<R>) cCache.get(key);
            if (factory == null) {
                factory = new StubFactoryGenerator<R>(type, remoteInfo).generateFactory();
                cCache.put(key, factory);
            }
            return factory;
        }
    }

    private final Class<R> mType;
    private final RemoteInfo mLocalInfo;
    private final RemoteInfo mRemoteInfo;

    private StubFactoryGenerator(Class<R> type, RemoteInfo remoteInfo) {
        mType = type;
        mLocalInfo = RemoteIntrospector.examine(type);
        if (remoteInfo == null) {
            remoteInfo = mLocalInfo;
        }
        mRemoteInfo = remoteInfo;
    }

    private StubFactory<R> generateFactory() {
        Class<? extends R> stubClass = generateStub();

        try {
            StubFactory<R> factory =  new Factory<R>(stubClass.getConstructor(StubSupport.class));
            CodeBuilderUtil.invokeInitMethod(stubClass, factory, mRemoteInfo);
            return factory;
        } catch (IllegalAccessException e) {
            throw new Error(e);
        } catch (InvocationTargetException e) {
            throw new Error(e);
        } catch (NoSuchMethodException e) {
            NoSuchMethodError nsme = new NoSuchMethodError();
            nsme.initCause(e);
            throw nsme;
        }
    }

    private Class<? extends R> generateStub() {
        ClassInjector ci = ClassInjector.create
            (CodeBuilderUtil.cleanClassName(mRemoteInfo.getName()) + "$Stub",
             mType.getClassLoader());

        ClassFile cf = new ClassFile(ci.getClassName());
        cf.addInterface(mType);
        cf.setSourceFile(StubFactoryGenerator.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        final TypeDesc identifierType = TypeDesc.forClass(Identifier.class);
        final TypeDesc stubSupportType = TypeDesc.forClass(StubSupport.class);
        final TypeDesc invConnectionType = TypeDesc.forClass(InvocationConnection.class);
        final TypeDesc invInType = TypeDesc.forClass(InvocationInputStream.class);
        final TypeDesc invOutType = TypeDesc.forClass(InvocationOutputStream.class);
        final TypeDesc unimplementedExType = TypeDesc.forClass(UnimplementedMethodException.class);
        final TypeDesc throwableType = TypeDesc.forClass(Throwable.class);
        final TypeDesc classType = TypeDesc.forClass(Class.class);

        // Add fields
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true), STUB_SUPPORT_NAME, stubSupportType);
        }

        // Add static method to assign identifiers.
        CodeBuilderUtil.addInitMethodAndFields(cf, mRemoteInfo);

        // Add constructor
        {
            MethodInfo mi = cf.addConstructor(Modifiers.PUBLIC, new TypeDesc[] {stubSupportType});
            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.invokeSuperConstructor(null);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            b.storeField(STUB_SUPPORT_NAME, stubSupportType);

            b.returnVoid();
        }

        // Implement all methods provided by server, including ones not defined
        // by local interface. This allows the server to upgrade before the
        // client, making new methods available via reflection.

        // Track which exception converting methods need to be created.
        Map<Class, String> exceptionConverters = new HashMap<Class, String>();

        int methodOrdinal = -1;
        for (RemoteMethod method : mRemoteInfo.getRemoteMethods()) {
            methodOrdinal++;

            RemoteMethod localMethod;
            {
                RemoteParameter[] params = method.getParameterTypes()
                    .toArray(new RemoteParameter[method.getParameterTypes().size()]);
                try {
                    localMethod = mLocalInfo.getRemoteMethod(method.getName(), params);
                } catch (NoSuchMethodException e) {
                    localMethod = null;
                }
            }

            TypeDesc returnDesc = CodeBuilderUtil.getTypeDesc(method.getReturnType());
            TypeDesc[] paramDescs = CodeBuilderUtil.getTypeDescs(method.getParameterTypes());

            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, method.getName(), returnDesc, paramDescs);

            TypeDesc[] exceptionDescs = CodeBuilderUtil.getTypeDescs(method.getExceptionTypes());
            for (TypeDesc desc : exceptionDescs) {
                mi.addException(desc);
            }

            // Prefer locally declared exception for remote failure.
            TypeDesc remoteFailureExType;
            if (localMethod != null) {
                remoteFailureExType = TypeDesc.forClass
                    (localMethod.getRemoteFailureException().getType());
            } else {
                remoteFailureExType = TypeDesc.forClass
                    (method.getRemoteFailureException().getType());
            }

            CodeBuilder b = new CodeBuilder(mi);

            // Create connection for invoking remote method.
            b.loadThis();
            b.loadField(STUB_SUPPORT_NAME, stubSupportType);
            b.loadConstant(remoteFailureExType);
            b.invokeInterface(stubSupportType, "invoke", invConnectionType,
                              new TypeDesc[] {classType});
            LocalVariable conVar = b.createLocalVariable(null, invConnectionType);
            b.storeLocal(conVar);

            Label invokeStart = b.createLabel().setLocation();

            // Write method identifier to connection.
            b.loadLocal(conVar);
            b.invokeInterface(invConnectionType, "getOutputStream", invOutType, null);
            LocalVariable invOutVar = b.createLocalVariable(null, invOutType);
            b.storeLocal(invOutVar);

            CodeBuilderUtil.loadMethodID(b, methodOrdinal);
            b.loadLocal(invOutVar);
            b.invokeVirtual(identifierType, "write", null,
                            new TypeDesc[] {TypeDesc.forClass(DataOutput.class)});

            if (paramDescs.length > 0) {
                // Write parameters to connection.

                boolean lookForPipe = method.isAsynchronous();

                int i = 0;
                for (RemoteParameter paramType : method.getParameterTypes()) {
                    if (lookForPipe && Pipe.class.isAssignableFrom(paramType.getType())) {
                        lookForPipe = false;
                        // Don't pass the Pipe to server.
                    } else {
                        CodeBuilderUtil.writeParam(b, paramType, invOutVar, b.getParameter(i));
                    }
                    i++;
                }
            }

            b.loadLocal(invOutVar);
            b.invokeVirtual(invOutType, "flush", null, null);

            Label invokeEnd;

            if (method.isAsynchronous()) {
                invokeEnd = b.createLabel().setLocation();

                if (returnDesc != null && Pipe.class.isAssignableFrom(returnDesc.toClass())) {
                    // Return connection as a Pipe.
                    b.loadLocal(conVar);
                    b.returnValue(returnDesc);
                } else {
                    // Finished with connection.
                    b.loadThis();
                    b.loadField(STUB_SUPPORT_NAME, stubSupportType);
                    b.loadLocal(conVar);
                    b.invokeInterface(stubSupportType, "finished", null,
                                      new TypeDesc[] {invConnectionType});

                    if (returnDesc == null) {
                        b.returnVoid();
                    } else {
                        // Return empty value for asynchronous method.
                        switch (returnDesc.getTypeCode()) {
                        case TypeDesc.BYTE_CODE:
                        case TypeDesc.SHORT_CODE:
                        case TypeDesc.CHAR_CODE:
                        case TypeDesc.INT_CODE:
                            b.loadConstant(0);
                            break;
                        case TypeDesc.LONG_CODE:
                            b.loadConstant(0L);
                            break;
                        case TypeDesc.FLOAT_CODE:
                            b.loadConstant(0.0f);
                            break;
                        case TypeDesc.DOUBLE_CODE:
                            b.loadConstant(0.0d);
                            break;
                        case TypeDesc.BOOLEAN_CODE:
                            b.loadConstant(false);
                            break;
                        default:
                            b.loadNull();
                            break;
                        }
                        b.returnValue(returnDesc);
                    }
                }
            } else {
                // Read response.
                b.loadLocal(conVar);
                b.invokeInterface(invConnectionType, "getInputStream", invInType, null);
                LocalVariable invInVar = b.createLocalVariable(null, invInType);
                b.storeLocal(invInVar);

                LocalVariable throwableVar = b.createLocalVariable(null, throwableType);

                b.loadLocal(invInVar);
                b.invokeVirtual(invInType, "readThrowable", throwableType, null);
                b.storeLocal(throwableVar);

                // Finished with connection.
                invokeEnd = b.createLabel().setLocation();
                b.loadThis();
                b.loadField(STUB_SUPPORT_NAME, stubSupportType);
                b.loadLocal(conVar);
                b.invokeInterface(stubSupportType, "finished", null,
                                  new TypeDesc[] {invConnectionType});

                b.loadLocal(throwableVar);
                Label normalResponse = b.createLabel();
                b.ifNullBranch(normalResponse, true);
                b.loadLocal(throwableVar);
                b.throwObject();
                
                normalResponse.setLocation();
                if (returnDesc == null) {
                    b.returnVoid();
                } else {
                    CodeBuilderUtil.readParam(b, method.getReturnType(), invInVar);
                    b.returnValue(returnDesc);
                }
            }

            // If any invocation exception, indicate connection failed.
            {
                b.exceptionHandler(invokeStart, invokeEnd, Throwable.class.getName());
                LocalVariable throwableVar = b.createLocalVariable(null, throwableType);
                b.storeLocal(throwableVar);

                b.loadThis();
                b.loadField(STUB_SUPPORT_NAME, stubSupportType);
                b.loadConstant(remoteFailureExType);
                b.loadLocal(conVar);
                b.loadLocal(throwableVar);
                b.invokeInterface(stubSupportType, "failed", throwableType,
                                  new TypeDesc[] {classType, invConnectionType, throwableType});
                b.throwObject();
            }
        }

        // Methods unimplemented by server throw UnimplementedMethodException

        for (RemoteMethod localMethod : mLocalInfo.getRemoteMethods()) {
            List<? extends RemoteParameter> paramList = localMethod.getParameterTypes();
            try {
                RemoteParameter[] paramTypes = new RemoteParameter[paramList.size()];
                paramList.toArray(paramTypes);

                RemoteMethod remoteMethod =
                    mRemoteInfo.getRemoteMethod(localMethod.getName(), paramTypes);

                if (CodeBuilderUtil.equalTypes(remoteMethod.getReturnType(),
                                               localMethod.getReturnType()))
                {
                    // Method has been implemented.
                    continue;
                }

                // If this point is reached, server does not implement method
                // because return type differs.
            } catch (NoSuchMethodException e) {
                // Server does not have this method.
            }

            TypeDesc returnDesc = CodeBuilderUtil.getTypeDesc(localMethod.getReturnType());
            TypeDesc[] paramDescs = CodeBuilderUtil.getTypeDescs(localMethod.getParameterTypes());

            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, localMethod.getName(), returnDesc, paramDescs);

            TypeDesc[] exceptionDescs = CodeBuilderUtil
                .getTypeDescs(localMethod.getExceptionTypes());

            for (TypeDesc desc : exceptionDescs) {
                mi.addException(desc);
            }

            CodeBuilder b = new CodeBuilder(mi);

            b.newObject(unimplementedExType);
            b.dup();
            b.loadConstant(mi.getMethodDescriptor().toMethodSignature(localMethod.getName()));
            b.invokeConstructor(unimplementedExType, new TypeDesc[] {TypeDesc.STRING});
            b.throwObject();
        }

        // Override Object.hashCode method to delegate to support.
        {
            MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "hashCode", TypeDesc.INT, null);
            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.loadField(STUB_SUPPORT_NAME, stubSupportType);
            b.invokeInterface(stubSupportType, "stubHashCode", TypeDesc.INT, null);
            b.returnValue(TypeDesc.INT);
        }

        // Override Object.equals method to delegate to support.
        {
            MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "equals", TypeDesc.BOOLEAN,
                                         new TypeDesc[] {TypeDesc.OBJECT});
            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            Label notIdentical = b.createLabel();
            b.ifEqualBranch(notIdentical, false);
            b.loadConstant(true);
            b.returnValue(TypeDesc.BOOLEAN);

            notIdentical.setLocation();

            b.loadLocal(b.getParameter(0));
            b.instanceOf(cf.getType());
            Label notInstance = b.createLabel();
            b.ifZeroComparisonBranch(notInstance, "==");

            b.loadThis();
            b.loadField(STUB_SUPPORT_NAME, stubSupportType);
            b.loadLocal(b.getParameter(0));
            b.checkCast(cf.getType());
            b.loadField(cf.getType(), STUB_SUPPORT_NAME, stubSupportType);
            b.invokeInterface(stubSupportType, "stubEquals", TypeDesc.BOOLEAN,
                              new TypeDesc[] {stubSupportType});
            b.returnValue(TypeDesc.BOOLEAN);

            notInstance.setLocation();
            b.loadConstant(false);
            b.returnValue(TypeDesc.BOOLEAN);
        }

        // Override Object.toString method to delegate to support.
        {
            MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "toString", TypeDesc.STRING, null);
            CodeBuilder b = new CodeBuilder(mi);

            b.loadConstant(mRemoteInfo.getName() + '@');

            b.loadThis();
            b.loadField(STUB_SUPPORT_NAME, stubSupportType);
            b.invokeInterface(stubSupportType, "stubToString", TypeDesc.STRING, null);

            b.invokeVirtual(TypeDesc.STRING, "concat", TypeDesc.STRING,
                            new TypeDesc[] {TypeDesc.STRING});

            b.returnValue(TypeDesc.STRING);
        }

        // Define remaining static exception converting methods.
        for (Map.Entry<Class, String> entry : exceptionConverters.entrySet()) {
            MethodInfo mi = cf.addMethod(Modifiers.PRIVATE.toStatic(true), entry.getValue(),
                                         throwableType, new TypeDesc[] {throwableType});
            CodeBuilder b = new CodeBuilder(mi);

            TypeDesc exType = TypeDesc.forClass(entry.getKey());

            b.newObject(exType);
            b.dup();
            b.loadLocal(b.getParameter(0));
            b.invokeVirtual(TypeDesc.forClass(Throwable.class), "getMessage",
                            TypeDesc.STRING, null);
            b.loadLocal(b.getParameter(0));
            b.invokeConstructor(exType, new TypeDesc[] {TypeDesc.STRING, throwableType});
            b.returnValue(exType);
        }

        return ci.defineClass(cf);
    }

    private static class Factory<R extends Remote> implements StubFactory<R> {
        private final Constructor<? extends R> mStubCtor;

        Factory(Constructor<? extends R> ctor) {
            mStubCtor = ctor;
        }

        public R createStub(StubSupport support) {
            Throwable error;
            try {
                return mStubCtor.newInstance(support);
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
