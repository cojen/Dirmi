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

import java.util.concurrent.Future;

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

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.CallMode;
import org.cojen.dirmi.Pipe;

import org.cojen.dirmi.info.RemoteInfo;
import org.cojen.dirmi.info.RemoteIntrospector;
import org.cojen.dirmi.info.RemoteMethod;
import org.cojen.dirmi.info.RemoteParameter;

import static org.cojen.dirmi.core.CodeBuilderUtil.*;

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
        CodeBuilderUtil.IdentifierSet.setMethodIds(mRemoteInfo);
        try {
            Class<? extends R> stubClass = generateStub();
            try {
                StubFactory<R> factory = new Factory<R>
                    (stubClass.getConstructor(StubSupport.class));
                invokeFactoryRefMethod(stubClass, factory);
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
        } finally {
            CodeBuilderUtil.IdentifierSet.clearIds();
        }
    }

    private Class<? extends R> generateStub() {
        ClassInjector ci = ClassInjector.create
            (cleanClassName(mRemoteInfo.getName()) + "$Stub",
             mType.getClassLoader());

        ClassFile cf = new ClassFile(ci.getClassName());
        cf.addInterface(mType);
        cf.setSourceFile(StubFactoryGenerator.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        // Add fields
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true), STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
        }

        // Add static method to assign identifiers.
        addInitMethodAndFields(cf, mRemoteInfo);

        // Add constructor
        {
            MethodInfo mi = cf.addConstructor(Modifiers.PUBLIC, new TypeDesc[]{STUB_SUPPORT_TYPE});
            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.invokeSuperConstructor(null);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            b.storeField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);

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

            TypeDesc returnDesc = getTypeDesc(method.getReturnType());
            TypeDesc[] paramDescs = getTypeDescs(method.getParameterTypes());

            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, method.getName(), returnDesc, paramDescs);

            TypeDesc[] exceptionDescs = getTypeDescs(method.getExceptionTypes());
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

            final CodeBuilder b = new CodeBuilder(mi);

            // Default timeout for remote method invocation.
            final long timeout = method.getTimeout();
            final TimeUnit timeoutUnit = method.getTimeoutUnit();
            TypeDesc timeoutType = TypeDesc.LONG;

            // Try to find any timeout parameters.
            LocalVariable timeoutVar = null;
            LocalVariable timeoutUnitVar = null;
            {
                int i = 0;
                for (RemoteParameter paramType : method.getParameterTypes()) {
                    if (paramType.isTimeout()) {
                        timeoutVar = b.getParameter(i);
                        TypeDesc desc = timeoutVar.getType();
                        if (desc == TypeDesc.FLOAT || desc == TypeDesc.DOUBLE) {
                            timeoutType = TypeDesc.DOUBLE;
                        }
                    } else if (paramType.isTimeoutUnit()) {
                        timeoutUnitVar = b.getParameter(i);
                    }
                    i++;
                }
            }

            final boolean noTimeout = timeout < 0 && timeoutVar == null;
            final LocalVariable originalTimeoutVar = timeoutVar;

            // Create channel for invoking remote method.
            b.loadThis();
            b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
            b.loadConstant(remoteFailureExType);
            b.invokeInterface(STUB_SUPPORT_TYPE, "prepare", INV_CHANNEL_TYPE,
                              new TypeDesc[] {CLASS_TYPE});
            LocalVariable channelVar = b.createLocalVariable(null, INV_CHANNEL_TYPE);
            b.storeLocal(channelVar);

            // Call invoke to write to channel.
            LocalVariable closeTaskVar;
            b.loadThis();
            b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
            b.loadConstant(remoteFailureExType);
            b.loadLocal(channelVar);
            if (noTimeout) {
                closeTaskVar = null;
                b.invokeInterface(STUB_SUPPORT_TYPE, "invoke", null,
                                  new TypeDesc[] {CLASS_TYPE, INV_CHANNEL_TYPE});
            } else {
                timeoutVar = genLoadTimeoutVars
                    (b, true, timeout, timeoutUnit, timeoutType, timeoutVar, timeoutUnitVar);

                closeTaskVar = b.createLocalVariable(null, FUTURE_TYPE);
                b.invokeInterface(STUB_SUPPORT_TYPE, "invoke", FUTURE_TYPE,
                                  new TypeDesc[] {CLASS_TYPE, INV_CHANNEL_TYPE,
                                                  timeoutType, TIME_UNIT_TYPE});
                b.storeLocal(closeTaskVar);
            }

            LocalVariable futureVar = null;
            if (method.isAsynchronous() &&
                returnDesc != null && Future.class == returnDesc.toClass())
            {
                futureVar = b.createLocalVariable(null, TypeDesc.forClass(Future.class));
                b.loadThis();
                b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
                b.invokeInterface(STUB_SUPPORT_TYPE, "createFuture", futureVar.getType(), null);
                b.storeLocal(futureVar);
            }

            final Label invokeStart = b.createLabel().setLocation();

            // Write method identifier to channel.
            b.loadLocal(channelVar);
            b.invokeInterface(INV_CHANNEL_TYPE, "getOutputStream", INV_OUT_TYPE, null);
            LocalVariable invOutVar = b.createLocalVariable(null, INV_OUT_TYPE);
            b.storeLocal(invOutVar);

            loadMethodID(b, methodOrdinal);
            b.loadLocal(invOutVar);
            b.invokeVirtual(IDENTIFIER_TYPE, "write", null, new TypeDesc[] {DATA_OUTPUT_TYPE});

            if (futureVar != null) {
                // Write the Future first to allow any parameter reading
                // problems to be reported as an exception.
                b.loadLocal(invOutVar);
                b.loadLocal(futureVar);
                b.invokeVirtual(invOutVar.getType(), "writeUnshared", null,
                                new TypeDesc[] {TypeDesc.OBJECT});
            }

            if (paramDescs.length > 0) {
                // Write parameters to channel.

                boolean lookForPipe = method.isAsynchronous() &&
                    returnDesc != null && Pipe.class == returnDesc.toClass();

                int i = 0;
                for (RemoteParameter paramType : method.getParameterTypes()) {
                    if (lookForPipe && Pipe.class == paramType.getType()) {
                        lookForPipe = false;
                        // Don't pass the Pipe to server.
                    } else {
                        LocalVariable param = b.getParameter(i);
                        if (param == originalTimeoutVar) {
                            // Use replacement.
                            param = timeoutVar;
                        }
                        writeParam(b, paramType, invOutVar, param);
                    }
                    i++;
                }
            }

            if (method.getAsynchronousCallMode() != CallMode.EVENTUAL) {
                b.loadLocal(invOutVar);
                b.invokeVirtual(INV_OUT_TYPE, "flush", null, null);
            }

            final Label invokeEnd;

            if (method.getAsynchronousCallMode() == CallMode.ACKNOWLEDGED) {
                // Read acknowledgement.
                b.loadLocal(channelVar);
                b.invokeInterface(INV_CHANNEL_TYPE, "getInputStream", INV_IN_TYPE, null);
                b.invokeVirtual(INV_IN_TYPE, "readThrowable", THROWABLE_TYPE, null);
                // Discard throwable since none is expected.
                b.pop();
            }

            if (method.isAsynchronous()) {
                invokeEnd = b.createLabel().setLocation();

                if (returnDesc != null && Pipe.class == returnDesc.toClass()) {
                    // Return channel; as a Pipe.
                    b.loadLocal(channelVar);
                    b.returnValue(returnDesc);
                } else if (futureVar != null) {
                    b.loadLocal(futureVar);
                    b.returnValue(returnDesc);
                } else if (method.isBatched() && returnDesc != null &&
                           Remote.class.isAssignableFrom(returnDesc.toClass()))
                {
                    // Return a remote object from a batched method.
                    b.loadThis();
                    b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
                    b.loadConstant(remoteFailureExType);
                    b.loadLocal(channelVar);
                    b.loadConstant(returnDesc);
                    b.invokeInterface(STUB_SUPPORT_TYPE, "createBatchedRemote",
                                      TypeDesc.forClass(Remote.class),
                                      new TypeDesc[] {CLASS_TYPE, INV_CHANNEL_TYPE, CLASS_TYPE});
                    b.checkCast(returnDesc);

                    // Finished with channel.
                    genBatched(b, channelVar, closeTaskVar);

                    b.returnValue(returnDesc);
                } else {
                    // Finished with channel.
                    if (method.isBatched()) {
                        genBatched(b, channelVar, closeTaskVar);
                    } else {
                        genFinished(b, channelVar, closeTaskVar);
                    }

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
                b.loadLocal(channelVar);
                b.invokeInterface(INV_CHANNEL_TYPE, "getInputStream", INV_IN_TYPE, null);
                LocalVariable invInVar = b.createLocalVariable(null, INV_IN_TYPE);
                b.storeLocal(invInVar);

                LocalVariable throwableVar = b.createLocalVariable(null, THROWABLE_TYPE);

                b.loadLocal(invInVar);
                b.invokeVirtual(INV_IN_TYPE, "readThrowable", THROWABLE_TYPE, null);
                b.storeLocal(throwableVar);

                b.loadLocal(throwableVar);
                Label abnormalResponse = b.createLabel();
                b.ifNullBranch(abnormalResponse, false);

                if (returnDesc == null) {
                    invokeEnd = b.createLabel().setLocation();
                    // Finished with channel.
                    genFinished(b, channelVar, closeTaskVar);
                    b.returnVoid();
                } else {
                    readParam(b, method.getReturnType(), invInVar);
                    invokeEnd = b.createLabel().setLocation();
                    // Finished with channel.
                    genFinished(b, channelVar, closeTaskVar);
                    b.returnValue(returnDesc);
                }

                abnormalResponse.setLocation();
                // Finished with channel.
                genFinished(b, channelVar, closeTaskVar);
                b.loadLocal(throwableVar);
                b.throwObject();
            }

            // If any invocation exception, indicate channel failed.
            {
                b.exceptionHandler(invokeStart, invokeEnd, Throwable.class.getName());
                LocalVariable throwableVar = b.createLocalVariable(null, THROWABLE_TYPE);
                b.storeLocal(throwableVar);

                b.loadThis();
                b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
                b.loadConstant(remoteFailureExType);
                b.loadLocal(channelVar);
                b.loadLocal(throwableVar);
                if (noTimeout) {
                    b.invokeInterface(STUB_SUPPORT_TYPE, "failed", THROWABLE_TYPE,
                                      new TypeDesc[]{CLASS_TYPE, INV_CHANNEL_TYPE,THROWABLE_TYPE});
                } else {
                    genLoadTimeoutVars(b, false, timeout, timeoutUnit, timeoutType,
                                       originalTimeoutVar, timeoutUnitVar);
                    b.loadLocal(closeTaskVar);
                    b.invokeInterface(STUB_SUPPORT_TYPE, "failed", THROWABLE_TYPE,
                                      new TypeDesc[]{CLASS_TYPE, INV_CHANNEL_TYPE,THROWABLE_TYPE,
                                                     timeoutType, TIME_UNIT_TYPE, FUTURE_TYPE});
                }
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

                if (equalTypes(remoteMethod.getReturnType(), localMethod.getReturnType())) {
                    // Method has been implemented.
                    continue;
                }

                // If this point is reached, server does not implement method
                // because return type differs.
            } catch (NoSuchMethodException e) {
                // Server does not have this method.
            }

            TypeDesc returnDesc = getTypeDesc(localMethod.getReturnType());
            TypeDesc[] paramDescs = getTypeDescs(localMethod.getParameterTypes());

            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, localMethod.getName(), returnDesc, paramDescs);

            TypeDesc[] exceptionDescs = getTypeDescs(localMethod.getExceptionTypes());

            for (TypeDesc desc : exceptionDescs) {
                mi.addException(desc);
            }

            CodeBuilder b = new CodeBuilder(mi);

            b.newObject(UNIMPLEMENTED_EX_TYPE);
            b.dup();
            b.loadConstant(mi.getMethodDescriptor().toMethodSignature(localMethod.getName()));
            b.invokeConstructor(UNIMPLEMENTED_EX_TYPE, new TypeDesc[] {TypeDesc.STRING});
            b.throwObject();
        }

        // Override Object.hashCode method to delegate to support.
        {
            MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "hashCode", TypeDesc.INT, null);
            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
            b.invokeInterface(STUB_SUPPORT_TYPE, "stubHashCode", TypeDesc.INT, null);
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
            b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
            b.loadLocal(b.getParameter(0));
            b.checkCast(cf.getType());
            b.loadField(cf.getType(), STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
            b.invokeInterface(STUB_SUPPORT_TYPE, "stubEquals", TypeDesc.BOOLEAN,
                              new TypeDesc[] {STUB_SUPPORT_TYPE});
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
            b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
            b.invokeInterface(STUB_SUPPORT_TYPE, "stubToString", TypeDesc.STRING, null);

            b.invokeVirtual(TypeDesc.STRING, "concat", TypeDesc.STRING,
                            new TypeDesc[] {TypeDesc.STRING});

            b.returnValue(TypeDesc.STRING);
        }

        // Define remaining static exception converting methods.
        for (Map.Entry<Class, String> entry : exceptionConverters.entrySet()) {
            MethodInfo mi = cf.addMethod(Modifiers.PRIVATE.toStatic(true), entry.getValue(),
                                         THROWABLE_TYPE, new TypeDesc[] {THROWABLE_TYPE});
            CodeBuilder b = new CodeBuilder(mi);

            TypeDesc exType = TypeDesc.forClass(entry.getKey());

            b.newObject(exType);
            b.dup();
            b.loadLocal(b.getParameter(0));
            b.invokeVirtual(THROWABLE_TYPE, "getMessage", TypeDesc.STRING, null);
            b.loadLocal(b.getParameter(0));
            b.invokeConstructor(exType, new TypeDesc[] {TypeDesc.STRING, THROWABLE_TYPE});
            b.returnValue(exType);
        }

        return ci.defineClass(cf);
    }

    /**
     * @param replaceNull when true, replace nulls and store back
     * into local variables
     * @return replacement for timeoutVar
     */
    private LocalVariable genLoadTimeoutVars
        (CodeBuilder b, boolean replaceNull,
         long timeout, TimeUnit timeoutUnit, TypeDesc timeoutType,
         LocalVariable timeoutVar, LocalVariable timeoutUnitVar)
    {
        if (timeoutVar == null) {
            b.loadConstant(timeout);
        } else {
            TypeDesc desc = timeoutVar.getType();
            if (desc.isPrimitive()) {
                b.loadLocal(timeoutVar);
                b.convert(desc, timeoutType);
            } else {
                b.loadLocal(timeoutVar);

                Label ready = b.createLabel();
                Label notNull = b.createLabel();
                b.ifNullBranch(notNull, false);

                final LocalVariable originalTimeoutVar = timeoutVar;

                if (replaceNull) {
                    if (genLoadConstantTimeoutValue(b, timeout, timeoutType, timeoutVar)) {
                        // Create a replacement variable if precision loss.
                        timeoutVar = b.createLocalVariable(null, timeoutVar.getType());
                    }
                    b.convert(timeoutVar.getType().toPrimitiveType(), timeoutVar.getType());
                    b.storeLocal(timeoutVar);
                }

                // Use the high precision value for the actual timeout.
                b.loadConstant(timeout);
                b.branch(ready);

                notNull.setLocation();
                b.loadLocal(originalTimeoutVar);
                if (originalTimeoutVar != timeoutVar) {
                    b.storeLocal(timeoutVar);
                    b.loadLocal(originalTimeoutVar);
                }
                b.convert(desc, timeoutType);

                ready.setLocation();
            }
        }

        if (timeoutUnitVar == null) {
            b.loadStaticField(TIME_UNIT_TYPE, timeoutUnit.name(), TIME_UNIT_TYPE);
        } else {
            b.loadLocal(timeoutUnitVar);
            if (replaceNull) {
                Label notNull = b.createLabel();
                b.ifNullBranch(notNull, false);
                b.loadStaticField(TIME_UNIT_TYPE, timeoutUnit.name(), TIME_UNIT_TYPE);
                b.storeLocal(timeoutUnitVar);
                notNull.setLocation();
                b.loadLocal(timeoutUnitVar);
            }
        }

        return timeoutVar;
    }

    /**
     * @return true if precision loss
     */
    private boolean genLoadConstantTimeoutValue(CodeBuilder b, long timeout, TypeDesc timeoutType,
                                                LocalVariable timeoutVar)
    {
        switch (timeoutVar.getType().toPrimitiveType().getTypeCode()) {
        case TypeDesc.BYTE_CODE:
            if (((byte) timeout) != timeout) {
                // Round to infinite.
                b.loadConstant((byte) -1);
                return true;
            } else {
                b.loadConstant((byte) timeout);
                return false;
            }

        case TypeDesc.SHORT_CODE:
            if (((short) timeout) != timeout) {
                // Round to infinite.
                b.loadConstant((short) -1);
                return true;
            } else {
                b.loadConstant((short) timeout);
                return false;
            }

        case TypeDesc.INT_CODE:
            if (((int) timeout) != timeout) {
                // Round to infinite.
                b.loadConstant(-1);
                return true;
            } else {
                b.loadConstant((int) timeout);
                return false;
            }

        case TypeDesc.LONG_CODE: default:
            b.loadConstant(timeout);
            return false;

        case TypeDesc.FLOAT_CODE:
            b.loadConstant((float) timeout);
            return ((float) timeout) != timeout;

        case TypeDesc.DOUBLE_CODE:
            b.loadConstant((double) timeout);
            return ((double) timeout) != timeout;
        }
    }

    private void genBatched(CodeBuilder b, LocalVariable channelVar, LocalVariable closeTaskVar) {
        b.loadThis();
        b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
        b.loadLocal(channelVar);
        if (closeTaskVar == null) {
            b.invokeInterface(STUB_SUPPORT_TYPE, "batched", null,
                              new TypeDesc[] {channelVar.getType()});
        } else {
            b.loadLocal(closeTaskVar);
            b.invokeInterface(STUB_SUPPORT_TYPE, "batched", null,
                              new TypeDesc[] {channelVar.getType(), closeTaskVar.getType()});
        }
    }

    private void genFinished(CodeBuilder b, LocalVariable channelVar, LocalVariable closeTaskVar) {
        b.loadThis();
        b.loadField(STUB_SUPPORT_NAME, STUB_SUPPORT_TYPE);
        b.loadLocal(channelVar);
        if (closeTaskVar == null) {
            b.invokeInterface(STUB_SUPPORT_TYPE, "finished", null,
                              new TypeDesc[] {channelVar.getType()});
        } else {
            b.loadLocal(closeTaskVar);
            b.invokeInterface(STUB_SUPPORT_TYPE, "finished", null,
                              new TypeDesc[] {channelVar.getType(), closeTaskVar.getType()});
        }
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
