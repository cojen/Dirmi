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
import java.io.IOException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.List;
import java.util.Map;

import java.util.concurrent.Semaphore;

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
    private static final String COMPLETION_FIELD_PREFIX = "completion_";
    private static final String STUB_SUPPORT_FIELD_NAME = "support";

    private static final Map<Object, StubFactory<?>> cCache;

    static {
        cCache = new SoftValuedHashMap();
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
            CodeBuilderUtil.invokeMethodIDInitMethod(stubClass, mRemoteInfo);
            return new Factory<R>(mType, stubClass);
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
        ClassInjector ci =
            ClassInjector.create(mType.getName() + "$Stub", mType.getClassLoader());

        ClassFile cf = new ClassFile(ci.getClassName());
        cf.addInterface(mType);
        cf.setSourceFile(StubFactoryGenerator.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        final TypeDesc remoteType = TypeDesc.forClass(mType);
        final TypeDesc identifierType = TypeDesc.forClass(Identifier.class);
        final TypeDesc stubSupportType = TypeDesc.forClass(StubSupport.class);
        final TypeDesc completionType = TypeDesc.forClass(Hidden.Completion.class);
        final TypeDesc completionArrayType = completionType.toArrayType();
        final TypeDesc remoteConnectionType = TypeDesc.forClass(RemoteConnection.class);
        final TypeDesc remoteInType = TypeDesc.forClass(RemoteInputStream.class);
        final TypeDesc remoteOutType = TypeDesc.forClass(RemoteOutputStream.class);
        final TypeDesc classType = TypeDesc.forClass(Class.class);
        final TypeDesc methodType = TypeDesc.forClass(Method.class);
        final TypeDesc remoteExType = TypeDesc.forClass(RemoteException.class);
        final TypeDesc unimplementedExType = TypeDesc.forClass(UnimplementedMethodException.class);

        // Add fields
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true),
                        STUB_SUPPORT_FIELD_NAME, stubSupportType);

            CodeBuilderUtil.addMethodIDFields(cf, mRemoteInfo);
        }

        // Add static method to assign identifiers.
        CodeBuilderUtil.addMethodIDInitMethod(cf, mRemoteInfo);

        // Add constructor
        {
            MethodInfo mi = cf.addConstructor
                (Modifiers.PUBLIC, new TypeDesc[] {stubSupportType});

            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.invokeSuperConstructor(null);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            b.storeField(STUB_SUPPORT_FIELD_NAME, stubSupportType);

            // Also define fields and assign to completion objects.
            int methodOrdinal = -1;
            for (RemoteMethod method : mRemoteInfo.getRemoteMethods()) {
                methodOrdinal++;
                if (method.isAsynchronous() && method.getAsynchronousPermits() >= 0) {
                    String fieldName = COMPLETION_FIELD_PREFIX + methodOrdinal;

                    cf.addField(Modifiers.PRIVATE.toFinal(true), fieldName, completionType);

                    b.loadThis();
                    b.newObject(completionType);
                    b.dup();
                    b.loadConstant(method.getAsynchronousPermits());
                    b.loadConstant(method.isAsynchronousFair());
                    b.invokeConstructor(completionType,
                                        new TypeDesc[] {TypeDesc.INT, TypeDesc.BOOLEAN});
                    b.storeField(fieldName, completionType);
                }
            }

            b.returnVoid();
        }

        // Implement all methods provided by server, including ones not defined
        // by local interface. This allows the server to upgrade before the
        // client, making new methods available via reflection.

        int methodOrdinal = -1;
        for (RemoteMethod method : mRemoteInfo.getRemoteMethods()) {
            methodOrdinal++;

            TypeDesc returnDesc = CodeBuilderUtil.getTypeDesc(method.getReturnType());
            TypeDesc[] paramDescs = CodeBuilderUtil.getTypeDescs(method.getParameterTypes());

            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, method.getName(), returnDesc, paramDescs);

            boolean interruptible = false;
            TypeDesc[] exceptionDescs = CodeBuilderUtil.getTypeDescs(method.getExceptionTypes());
            for (TypeDesc desc : exceptionDescs) {
                mi.addException(desc);
                if (desc.toClass() == InterruptedException.class) {
                    interruptible = true;
                }
            }

            CodeBuilder b = new CodeBuilder(mi);

            // Asynchronous method with limited permits must acquire semaphore.
            if (method.isAsynchronous() && method.getAsynchronousPermits() >= 0) {
                b.loadThis();
                b.loadField(COMPLETION_FIELD_PREFIX + methodOrdinal, completionType);
                if (interruptible) {
                    b.invokeVirtual(completionType, "acquire", null, null);
                } else {
                    b.invokeVirtual(completionType, "acquireUninterruptibly", null, null);
                }
            }

            Label tryStart = b.createLabel().setLocation();

            // Create connection for invoking remote method.
            b.loadThis();
            b.loadField(STUB_SUPPORT_FIELD_NAME, stubSupportType);
            b.invokeInterface(stubSupportType, "invoke", remoteConnectionType, null);
            LocalVariable conVar = b.createLocalVariable(null, remoteConnectionType);
            b.storeLocal(conVar);

            // Write method identifier to connection.
            b.loadLocal(conVar);
            b.invokeInterface(remoteConnectionType, "getOutputStream", remoteOutType, null);
            LocalVariable remoteOutVar = b.createLocalVariable(null, remoteOutType);
            b.storeLocal(remoteOutVar);

            CodeBuilderUtil.loadMethodID(b, methodOrdinal);
            b.loadLocal(remoteOutVar);
            b.invokeVirtual(identifierType, "write", null,
                            new TypeDesc[] {TypeDesc.forClass(DataOutput.class)});

            if (paramDescs.length > 0) {
                // Write parameters to connection.
                int i = 0;
                for (RemoteParameter paramType : method.getParameterTypes()) {
                    CodeBuilderUtil.writeParam(b, paramType, remoteOutVar, b.getParameter(i));
                    i++;
                }
            }

            if (method.isAsynchronous()) {
                if (method.getAsynchronousPermits() >= 0) {
                    // Write remote AsynchronousCompletion object to be called by server.
                    b.loadLocal(remoteOutVar);
                    b.loadThis();
                    b.loadField(COMPLETION_FIELD_PREFIX + methodOrdinal, completionType);
                    b.invokeVirtual
                        (remoteOutType, "writeObject", null, new TypeDesc[] {TypeDesc.OBJECT});
                }

                // Now close connection since no return value to read back.
                b.loadLocal(conVar);
                b.invokeInterface(remoteConnectionType, "close", null, null);

                if (returnDesc != null) {
                    // Asynchronous method should not have a return value, but
                    // this one does for some reason. Just return 0, false, or null.
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
                }
            } else {
                b.loadLocal(remoteOutVar);
                b.invokeVirtual(remoteOutType, "flush", null, null);
                
                // Read response.
                b.loadLocal(conVar);
                b.invokeInterface(remoteConnectionType, "getInputStream", remoteInType, null);
                LocalVariable remoteInVar = b.createLocalVariable(null, remoteInType);
                b.storeLocal(remoteInVar);

                b.loadLocal(remoteInVar);
                b.invokeVirtual(remoteInType, "readOk", TypeDesc.BOOLEAN, null);

                if (returnDesc != TypeDesc.BOOLEAN) {
                    b.pop();
                    if (returnDesc != null) {
                        CodeBuilderUtil.readParam(b, method.getReturnType(), remoteInVar);
                    }
                }

                // Assume server has closed connection.
            }

            if (returnDesc == null) {
                b.returnVoid();
            } else {
                b.returnValue(returnDesc);
            }

            Label tryEnd1 = b.createLabel().setLocation();

            if (method.isAsynchronous() && method.getAsynchronousPermits() >= 0) {
                // If any exception, close completion object.
                b.exceptionHandler(tryStart, tryEnd1, null);
                b.loadThis();
                b.loadField(COMPLETION_FIELD_PREFIX + methodOrdinal, completionType);
                b.invokeVirtual(completionType, "close", null, null);
                b.throwObject();
            }

            Label tryEnd2 = b.createLabel().setLocation();

            // Convert any IOException to a RemoteException.

            b.exceptionHandler(tryStart, tryEnd2, RemoteException.class.getName());
            // RemoteException is an IOException, but leave it as-is.
            b.throwObject();

            b.exceptionHandler(tryStart, tryEnd2, IOException.class.getName());
            LocalVariable exceptionVar =
                b.createLocalVariable(null, TypeDesc.forClass(IOException.class));
            b.storeLocal(exceptionVar);

            b.newObject(remoteExType);
            b.dup();
            b.loadLocal(exceptionVar);
            b.invokeVirtual(TypeDesc.forClass(Throwable.class), "getMessage",
                            TypeDesc.STRING, null);
            b.loadLocal(exceptionVar);
            b.invokeConstructor(remoteExType, new TypeDesc[] {TypeDesc.STRING,
                                                              TypeDesc.forClass(Throwable.class)});
            b.throwObject();
        }

        // Methods unimplemented by server throw UnimplementedMethodException

        for (RemoteMethod method : mLocalInfo.getRemoteMethods()) {
            List<? extends RemoteParameter> paramList = method.getParameterTypes();
            try {
                RemoteParameter[] paramTypes = new RemoteParameter[paramList.size()];
                paramList.toArray(paramTypes);
                // FIXME: check return type
                mRemoteInfo.getRemoteMethod(method.getName(), paramTypes);
                // Method has been implemented.
                continue;
            } catch (NoSuchMethodException e) {
                // Server does not have this method.
            }

            TypeDesc returnDesc = CodeBuilderUtil.getTypeDesc(method.getReturnType());
            TypeDesc[] paramDescs = CodeBuilderUtil.getTypeDescs(method.getParameterTypes());

            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, method.getName(), returnDesc, paramDescs);

            TypeDesc[] exceptionDescs = CodeBuilderUtil.getTypeDescs(method.getExceptionTypes());
            for (TypeDesc desc : exceptionDescs) {
                mi.addException(desc);
            }

            CodeBuilder b = new CodeBuilder(mi);
            
            b.newObject(unimplementedExType);
            b.dup();
            b.loadConstant(TypeDesc.forClass(mType));
            b.loadConstant(method.getName());
            b.loadConstant(paramList.size());
            b.newObject(classType.toArrayType());
            for (int i=0; i<paramList.size(); i++) {
                b.dup();
                b.loadConstant(i);
                b.loadConstant(CodeBuilderUtil.getTypeDesc(paramList.get(i)));
                b.storeToArray(classType);
            }
            b.invokeVirtual(classType, "getMethod", methodType,
                            new TypeDesc[] {TypeDesc.STRING, classType.toArrayType()});
            b.invokeConstructor(unimplementedExType, new TypeDesc[] {methodType});
            b.throwObject();
        }

        // Override Object.hashCode method to delegate to support.
        {
            MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "hashCode", TypeDesc.INT, null);
            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.loadField(STUB_SUPPORT_FIELD_NAME, stubSupportType);
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
            b.loadField(STUB_SUPPORT_FIELD_NAME, stubSupportType);
            b.loadLocal(b.getParameter(0));
            b.checkCast(cf.getType());
            b.loadField(cf.getType(), STUB_SUPPORT_FIELD_NAME, stubSupportType);
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

            b.loadConstant(mLocalInfo.getName() + '@');

            b.loadThis();
            b.loadField(STUB_SUPPORT_FIELD_NAME, stubSupportType);
            b.invokeInterface(stubSupportType, "stubToString", TypeDesc.STRING, null);

            b.invokeVirtual(TypeDesc.STRING, "concat", TypeDesc.STRING,
                            new TypeDesc[] {TypeDesc.STRING});

            b.returnValue(TypeDesc.STRING);
        }

        return ci.defineClass(cf);
    }

    private static class Factory<R extends Remote> implements StubFactory<R> {
        private final Class<R> mType;
        private final Constructor<? extends R> mStubCtor;

        Factory(Class<R> type, Class<? extends R> stubClass) throws NoSuchMethodException {
            mType = type;
            mStubCtor = stubClass.getConstructor(StubSupport.class);
        }

        public Class<R> getRemoteType() {
            return mType;
        }

        public Class<? extends R> getStubClass() {
            return mStubCtor.getDeclaringClass();
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

    private static class Hidden {
        public static class Completion implements AsynchronousCompletion {
            private volatile Semaphore mSemaphore;

            public Completion(int permits, boolean fair) {
                mSemaphore = new Semaphore(permits, fair);
            }

            // Called locally by stub.
            public void acquire() throws InterruptedException, RemoteException {
                Semaphore s = mSemaphore;
                if (s != null) {
                    s.acquire();
                    if (mSemaphore != null) {
                        return;
                    }
                }
                throw new RemoteException("Session closed");
            }

            // Called locally by stub.
            public void acquireUninterruptibly() throws RemoteException {
                Semaphore s = mSemaphore;
                if (s != null) {
                    s.acquireUninterruptibly();
                    if (mSemaphore != null) {
                        return;
                    }
                }
                throw new RemoteException("Session closed");
            }

            // Called remotely by server.
            public void completed() {
                Semaphore s = mSemaphore;
                if (s != null) {
                    s.release();
                }
            }

            // Called locally by stub or session.
            public void dispose() {
                Semaphore s = mSemaphore;
                mSemaphore = null;
                if (s != null) {
                    // Draining and releasing the max possible permits should
                    // cause all blocked threads to resume.
                    s.drainPermits();
                    s.release(Integer.MAX_VALUE);
                }
            }
        }
    }
}
