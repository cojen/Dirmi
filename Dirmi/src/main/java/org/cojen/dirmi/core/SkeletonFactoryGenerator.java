/*
 *  Copyright 2006-2010 Brian S O'Neill
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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

import java.io.DataInput;
import java.io.IOException;
import java.io.ObjectOutput;

import java.rmi.Remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.Future;

import java.util.concurrent.atomic.AtomicReference;

import java.security.AccessController;
import java.security.PrivilegedAction;

import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.Label;
import org.cojen.classfile.LocalVariable;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.RuntimeClassFile;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.KeyFactory;

import org.cojen.dirmi.CallMode;
import org.cojen.dirmi.Completion;
import org.cojen.dirmi.Link;
import org.cojen.dirmi.MalformedRemoteObjectException;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.SessionAware;

import org.cojen.dirmi.info.RemoteInfo;
import org.cojen.dirmi.info.RemoteIntrospector;
import org.cojen.dirmi.info.RemoteMethod;
import org.cojen.dirmi.info.RemoteParameter;

import org.cojen.dirmi.util.Cache;

import static org.cojen.dirmi.core.CodeBuilderUtil.*;

/**
 * Generates {@link SkeletonFactory} instances for any given Remote type.
 *
 * @author Brian S O'Neill
 */
public class SkeletonFactoryGenerator<R extends Remote> {
    private static final String SUPPORT_FIELD_NAME = "support";
    private static final String REMOTE_FIELD_NAME = "remote";
    private static final String ID_FIELD_NAME = "id";
    private static final String ORDERED_INVOKER_FIELD_NAME = "orderedInvoker";
    private static final String METHOD_FIELD_PREFIX = "method$";

    private static final Cache<Object, SkeletonFactory<?>> cCache;

    static {
        cCache = Cache.newSoftValueCache(17);
    }

    /**
     * Returns a new or cached SkeletonFactory.
     *
     * @param type
     * @throws IllegalArgumentException if type is null or malformed
     */
    public static <R extends Remote> SkeletonFactory<R> getSkeletonFactory(Class<R> type)
        throws IllegalArgumentException
    {
        RemoteInfo localInfo = RemoteIntrospector.examine(type);
        Object key = KeyFactory.createKey(new Object[] {type, localInfo.getInfoId()});

        synchronized (cCache) {
            SkeletonFactory<R> factory = (SkeletonFactory<R>) cCache.get(key);
            if (factory == null) {
                factory = new SkeletonFactoryGenerator<R>(localInfo, type).generateFactory();
                cCache.put(key, factory);
            }
            return factory;
        }
    }

    /**
     * Returns a new or cached SkeletonFactory for use with remote objects
     * served by batched methods.
     *
     * @param type
     * @param remoteInfo remote type as supported by remote server
     * @throws IllegalArgumentException if type is null or malformed
     */
    public static <R extends Remote> SkeletonFactory<R> getSkeletonFactory(Class<R> type,
                                                                           RemoteInfo remoteInfo)
    {
        Object key = KeyFactory.createKey(new Object[] {type, remoteInfo.getInfoId()});

        synchronized (cCache) {
            SkeletonFactory<R> factory = (SkeletonFactory<R>) cCache.get(key);
            if (factory == null) {
                factory = new SkeletonFactoryGenerator<R>(type, remoteInfo).generateFactory();
                cCache.put(key, factory);
            }
            return factory;
        }
    }

    private final Class<R> mType;
    private final RemoteInfo mInfo;

    private final RemoteInfo mLocalInfo;
    // Is set only if mLocalInfo is null.
    private final String mMalformedInfoMessage;

    private final AtomicReference<Object> mFactoryRef = new AtomicReference<Object>();

    private SkeletonFactoryGenerator(RemoteInfo localInfo, Class<R> type) {
        mType = type;
        mInfo = mLocalInfo = localInfo;
        mMalformedInfoMessage = null;
    }

    private SkeletonFactoryGenerator(Class<R> type, RemoteInfo remoteInfo) {
        mType = type;
        mInfo = remoteInfo;

        RemoteInfo localInfo;
        String malformed;

        try {
            localInfo = RemoteIntrospector.examine(type);
            malformed = null;
        } catch (IllegalArgumentException e) {
            localInfo = null;
            malformed = e.getMessage();
        }

        mLocalInfo = localInfo;
        mMalformedInfoMessage = malformed;
    }

    private SkeletonFactory<R> generateFactory() {
        if (mInfo.getRemoteMethods().isEmpty()) {
            return EmptySkeletonFactory.THE;
        }

        return AccessController.doPrivileged(new PrivilegedAction<SkeletonFactory<R>>() {
            public SkeletonFactory<R> run() {
                Class<? extends Skeleton> skeletonClass = generateSkeleton();
                try {
                    SkeletonFactory<R> factory = new Factory<R>
                        (skeletonClass.getConstructor
                         (VersionedIdentifier.class, SkeletonSupport.class, mType));
                    mFactoryRef.set(factory);
                    return factory;
                } catch (NoSuchMethodException e) {
                    NoSuchMethodError nsme = new NoSuchMethodError();
                    nsme.initCause(e);
                    throw nsme;
                }
            }
        });
    }

    private Class<? extends Skeleton> generateSkeleton() {
        RuntimeClassFile cf =
            createRuntimeClassFile(mType.getName() + "$Skeleton", mType.getClassLoader());
        cf.addInterface(Skeleton.class);
        cf.setSourceFile(SkeletonFactoryGenerator.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        final TypeDesc remoteType = TypeDesc.forClass(mType);

        // Add common fields.
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true), SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
            cf.addField(Modifiers.PRIVATE.toFinal(true), REMOTE_FIELD_NAME, remoteType);
        }

        // Add reference to factory.
        CodeBuilder staticInitBuilder = addStaticFactoryRefUnfinished(cf, mFactoryRef);

        // Add remote server access method.
        {
            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, "getRemoteServer", TypeDesc.forClass(Remote.class), null);
            CodeBuilder b = new CodeBuilder(mi);
            b.loadThis();
            b.loadField(REMOTE_FIELD_NAME, remoteType);
            b.returnValue(TypeDesc.OBJECT);
        }

        // Add the all-important invoke method.
        CodeBuilder invokeBuilder;
        {
            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, "invoke", TypeDesc.INT,
                 new TypeDesc[] {LINK_TYPE, TypeDesc.INT, INV_CHANNEL_TYPE, BATCH_INV_EX_TYPE});
            invokeBuilder = new CodeBuilder(mi);
        }

        Set<? extends RemoteMethod> methods = mInfo.getRemoteMethods();

        // Create a switch statement that operates on method ids.

        Map<Integer, RemoteMethod> caseMap =
            new LinkedHashMap<Integer, RemoteMethod>(methods.size());
        boolean hasOrderedMethods = false;
        boolean hasDisposer = false;
        for (RemoteMethod method : methods) {
            caseMap.put(method.getMethodId(), method);
            hasOrderedMethods |= method.isOrdered();
            hasDisposer |= method.isDisposer();
        }

        int caseCount = caseMap.size();
        int[] cases = new int[caseCount];
        Label[] switchLabels = new Label[caseCount];
        Label defaultLabel = invokeBuilder.createLabel();

        {
            int caseIndex = 0;
            for (Integer key : caseMap.keySet()) {
                cases[caseIndex] = key;
                switchLabels[caseIndex] = invokeBuilder.createLabel();
                caseIndex++;
            }
        }

        // Load methodId.
        invokeBuilder.loadLocal(invokeBuilder.getParameter(1));
        invokeBuilder.switchBranch(cases, switchLabels, defaultLabel);

        // Generate case for each method.

        Map<String, Integer> methodNames = new LinkedHashMap<String, Integer>();
        List<Label> disposerTryLabels = null;
        Label disposerGotoLabel = null;

        int caseIndex = 0;
        for (RemoteMethod method : caseMap.values()) {
            switchLabels[caseIndex++].setLocation();

            // Delegate work to a named method to make stack traces more
            // helpful. This method is not expected to conflict with any
            // inherited from Object, Skeleton, or Unreferenced.

            CodeBuilder b;
            {
                String name = generateMethodName(methodNames, method);
                TypeDesc[] params = new TypeDesc[] {INV_CHANNEL_TYPE, BATCH_INV_EX_TYPE};
                MethodInfo innerMethod = cf.addMethod
                    (Modifiers.PRIVATE, name, TypeDesc.INT, params);
                b = new CodeBuilder(innerMethod);

                invokeBuilder.loadThis();
                invokeBuilder.loadLocal(invokeBuilder.getParameter(2));
                invokeBuilder.loadLocal(invokeBuilder.getParameter(3));
                if (!method.isDisposer()) {
                    invokeBuilder.invokePrivate(name, TypeDesc.INT, params);
                    invokeBuilder.returnValue(TypeDesc.INT);
                } else {
                    if (disposerTryLabels == null) {
                        disposerTryLabels = new ArrayList<Label>();
                    }

                    disposerTryLabels.add(invokeBuilder.createLabel().setLocation());
                    invokeBuilder.invokePrivate(name, TypeDesc.INT, params);
                    disposerTryLabels.add(invokeBuilder.createLabel().setLocation());

                    if (disposerGotoLabel == null) {
                        disposerGotoLabel = invokeBuilder.createLabel();
                    }

                    invokeBuilder.branch(disposerGotoLabel);
                }
            }

            LocalVariable channelVar = b.getParameter(0);
            LocalVariable batchedExceptionVar = b.getParameter(1);
            LocalVariable sequenceVar = null;

            if (method.isOrdered()) {
                if (method.isAsynchronous() && !method.isBatched() && methodExists(method)) {
                    // OrderedInvoker might need to invoke via reflection.
                    TypeDesc methodType = TypeDesc.forClass(Method.class);

                    String fieldName = METHOD_FIELD_PREFIX + caseIndex;
                    cf.addField(Modifiers.PRIVATE.toStatic(true).toFinal(true),
                                fieldName, methodType);

                    staticInitBuilder.loadConstant(remoteType);
                    staticInitBuilder.loadConstant(method.getName());
                    TypeDesc[] paramTypes = getTypeDescs(method.getParameterTypes());
                    if (paramTypes.length == 0) {
                        staticInitBuilder.loadNull();
                    } else {
                        staticInitBuilder.loadConstant(paramTypes.length);
                        staticInitBuilder.newObject(CLASS_TYPE.toArrayType());
                        for (int i=0; i<paramTypes.length; i++) {
                            staticInitBuilder.dup();
                            staticInitBuilder.loadConstant(i);
                            staticInitBuilder.loadConstant(paramTypes[i]);
                            staticInitBuilder.storeToArray(TypeDesc.OBJECT);
                        }
                    }
                    staticInitBuilder.invokeVirtual
                        (CLASS_TYPE, "getMethod", methodType,
                         new TypeDesc[] {TypeDesc.STRING, CLASS_TYPE.toArrayType()});
                    staticInitBuilder.storeStaticField(fieldName, methodType);
                }

                b.loadLocal(channelVar);
                b.invokeInterface(channelVar.getType(), "readInt", TypeDesc.INT, null);
                sequenceVar = b.createLocalVariable(null, TypeDesc.INT);
                b.storeLocal(sequenceVar);
            }

            // Lazily created.
            LocalVariable invInVar = null;

            TypeDesc returnDesc = getTypeDesc(method.getReturnType());
            List<? extends RemoteParameter> paramTypes = method.getParameterTypes();

            LocalVariable completionVar = null;
            if (method.isAsynchronous() && returnDesc != null &&
                (Future.class == returnDesc.toClass() ||
                 Completion.class == returnDesc.toClass()))
            {
                // Read the Future completion object early.
                completionVar = b.createLocalVariable
                    (null, TypeDesc.forClass(RemoteCompletion.class));
                invInVar = invInVar(b, channelVar, invInVar);
                b.loadLocal(invInVar);
                b.invokeVirtual(invInVar.getType(), "readUnshared", TypeDesc.OBJECT, null);
                b.checkCast(completionVar.getType());
                b.storeLocal(completionVar);
            }

            // TODO: If has completionVar, catch exception from reading
            // parameters and report to client.

            // Push remote server on stack in preparation for a method to be
            // invoked on it.
            b.loadThis();
            b.loadField(REMOTE_FIELD_NAME, remoteType);

            boolean noPipeParam = true;

            if (!paramTypes.isEmpty()) {
                // Read parameters onto stack.

                boolean lookForPipe = method.isAsynchronous() &&
                    returnDesc != null && Pipe.class == returnDesc.toClass();

                for (int i=0; i<paramTypes.size(); i++) {
                    RemoteParameter paramType = paramTypes.get(i);
                    if (lookForPipe && Pipe.class == paramType.getType()) {
                        lookForPipe = false;
                        // Use channel as Pipe.
                        noPipeParam = false;
                        if (method.getAsynchronousCallMode() == CallMode.REQUEST_REPLY) {
                            // Convert to support request-reply.
                            b.loadThis();
                            b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
                            b.loadLocal(channelVar);
                            b.invokeInterface(SKEL_SUPPORT_TYPE, "requestReply", returnDesc,
                                              new TypeDesc[] {INV_CHANNEL_TYPE});
                        } else {
                            b.loadLocal(channelVar);
                        }
                    } else {
                        invInVar = invInVar(b, channelVar, invInVar);
                        readParam(b, paramType, invInVar);
                    }
                }
            }

            LocalVariable remoteTypeIdVar = null;
            LocalVariable remoteIdVar = null;

            boolean batchedRemote = method.isBatched() && returnDesc != null &&
                Remote.class.isAssignableFrom(returnDesc.toClass());

            if (batchedRemote) {
                // Read the type id and object id that was generated by client.
                invInVar = invInVar(b, channelVar, invInVar);
                b.loadLocal(invInVar);
                b.invokeStatic(IDENTIFIER_TYPE, "read", IDENTIFIER_TYPE,
                               new TypeDesc[] {TypeDesc.forClass(DataInput.class)});
                remoteTypeIdVar = b.createLocalVariable(null, IDENTIFIER_TYPE);
                b.storeLocal(remoteTypeIdVar);

                b.loadLocal(invInVar);
                b.invokeStatic(VERSIONED_IDENTIFIER_TYPE, "readAndUpdateRemoteVersion",
                               VERSIONED_IDENTIFIER_TYPE,
                               new TypeDesc[] {TypeDesc.forClass(DataInput.class)});
                remoteIdVar = b.createLocalVariable(null, VERSIONED_IDENTIFIER_TYPE);
                b.storeLocal(remoteIdVar);
            }

            if (method.getAsynchronousCallMode() == CallMode.ACKNOWLEDGED) {
                // Acknowledge request by writing null.
                b.loadLocal(channelVar);
                b.invokeInterface(INV_CHANNEL_TYPE, "getOutputStream", INV_OUT_TYPE, null);
                b.dup();
                b.loadNull();
                b.invokeVirtual(INV_OUT_TYPE, "writeThrowable", null,
                                new TypeDesc[] {THROWABLE_TYPE});
                b.invokeVirtual(INV_OUT_TYPE, "flush", null, null);
            }

            if (method.isAsynchronous() && !method.isBatched() && !method.isOrdered() &&
                noPipeParam)
            {
                // Call finished method before invocation.
                genFinishedAsync(b, channelVar);
            }

            // Generate code which invokes server side method.

            Label tryStart, tryEnd;
            {
                tryStart = b.createLabel().setLocation();

                // If a batched exception is pending, throw it now instead
                // of invoking method.
                b.loadLocal(batchedExceptionVar);
                Label noPendingException = b.createLabel();
                b.ifNullBranch(noPendingException, true);

                b.loadLocal(batchedExceptionVar);
                if (method.isBatched()) {
                    b.throwObject();
                } else {
                    // Throw cause as declared type or wrapped in
                    // UndeclaredThrowableException.

                    Class[] declaredExceptions = declaredExceptionTypes(method);
                    if (declaredExceptions == null || declaredExceptions.length == 0) {
                        b.loadNull();
                        b.invokeVirtual(BATCH_INV_EX_TYPE, "isCauseDeclared", TypeDesc.BOOLEAN,
                                        new TypeDesc[] {CLASS_TYPE.toArrayType()});
                    } else if (declaredExceptions.length == 1) {
                        b.loadConstant(TypeDesc.forClass(declaredExceptions[0]));
                        b.invokeVirtual(BATCH_INV_EX_TYPE, "isCauseDeclared", TypeDesc.BOOLEAN,
                                        new TypeDesc[] {CLASS_TYPE});
                    } else if (declaredExceptions.length == 2) {
                        b.loadConstant(TypeDesc.forClass(declaredExceptions[0]));
                        b.loadConstant(TypeDesc.forClass(declaredExceptions[1]));
                        b.invokeVirtual(BATCH_INV_EX_TYPE, "isCauseDeclared", TypeDesc.BOOLEAN,
                                        new TypeDesc[] {CLASS_TYPE, CLASS_TYPE});
                    } else {
                        b.loadConstant(declaredExceptions.length);
                        b.newObject(CLASS_TYPE.toArrayType());
                        for (int i=0; i<declaredExceptions.length; i++) {
                            Class exception = declaredExceptions[i];
                            b.dup();
                            b.loadConstant(i);
                            b.loadConstant(TypeDesc.forClass(exception));
                            b.storeToArray(CLASS_TYPE);
                        }
                     
                        b.invokeVirtual(BATCH_INV_EX_TYPE, "isCauseDeclared", TypeDesc.BOOLEAN,
                                        new TypeDesc[] {CLASS_TYPE.toArrayType()});
                    }

                    Label isDeclared = b.createLabel();
                    b.ifZeroComparisonBranch(isDeclared, "!=");
                    TypeDesc undecExType =
                        TypeDesc.forClass(UndeclaredThrowableException.class);
                    b.newObject(undecExType);
                    b.dup();
                    b.loadLocal(batchedExceptionVar);
                    b.invokeVirtual(BATCH_INV_EX_TYPE, "getCause", THROWABLE_TYPE, null);
                    b.dup();
                    b.invokeVirtual(THROWABLE_TYPE, "toString", TypeDesc.STRING, null);
                    b.invokeConstructor(undecExType,
                                        new TypeDesc[] {THROWABLE_TYPE, TypeDesc.STRING});
                    b.throwObject();

                    isDeclared.setLocation();
                    b.loadLocal(batchedExceptionVar);
                    b.invokeVirtual(BATCH_INV_EX_TYPE, "getCause", THROWABLE_TYPE, null);
                    b.throwObject();
                }

                noPendingException.setLocation();

                if (method.isOrdered() && methodExists(method)) {
                    if (!method.isAsynchronous() || method.isBatched()) {
                        genWaitForNext(b, sequenceVar);
                    } else {
                        Label isNext = b.createLabel();

                        genIsNext(b, sequenceVar);
                        b.ifZeroComparisonBranch(isNext, "!=");

                        TypeDesc orderedInvokerType = TypeDesc.forClass(OrderedInvoker.class);
                        TypeDesc methodType = TypeDesc.forClass(Method.class);

                        // Convert parameters and store in local variables.
                        LocalVariable[] paramVars;
                        if (paramTypes.isEmpty()) {
                            paramVars = null;
                        } else {
                            paramVars = new LocalVariable[paramTypes.size()];

                            for (int i=paramTypes.size(); --i>=0; ) {
                                Class paramType = paramTypes.get(i).getType();
                                TypeDesc paramDesc = TypeDesc.forClass(paramType);
                                TypeDesc objParamDesc = paramDesc.toObjectType();
                                LocalVariable paramVar = b.createLocalVariable(null, objParamDesc);
                                paramVars[i] = paramVar;
                                b.convert(paramDesc, objParamDesc);
                                b.storeLocal(paramVar);
                            }
                        }

                        // Discard reference to remote object.
                        b.pop();

                        // Prepare to invoke OrderedInvoker.addPendingMethod.
                        b.loadThis();
                        b.loadField(ORDERED_INVOKER_FIELD_NAME, orderedInvokerType);

                        b.loadLocal(sequenceVar);
                        b.loadStaticField(METHOD_FIELD_PREFIX + caseIndex, methodType);
                        b.loadThis();
                        b.loadField(REMOTE_FIELD_NAME, remoteType);

                        if (paramVars == null) {
                            b.loadNull();
                        } else {
                            b.loadConstant(paramVars.length);
                            b.newObject(TypeDesc.OBJECT.toArrayType());
                            for (int i=0; i<paramVars.length; i++) {
                                b.dup();
                                b.loadConstant(i);
                                b.loadLocal(paramVars[i]);
                                b.storeToArray(TypeDesc.OBJECT);
                            }
                        }

                        TypeDesc[] params;
                        if (completionVar == null) {
                            params = new TypeDesc[] {
                                TypeDesc.INT,
                                methodType,
                                TypeDesc.OBJECT,
                                TypeDesc.OBJECT.toArrayType()
                            };
                        } else {
                            b.loadThis();
                            b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
                            b.loadLocal(completionVar);

                            params = new TypeDesc[] {
                                TypeDesc.INT,
                                methodType,
                                TypeDesc.OBJECT,
                                TypeDesc.OBJECT.toArrayType(),
                                SKEL_SUPPORT_TYPE,
                                TypeDesc.forClass(RemoteCompletion.class)
                            };
                        }

                        b.invokeVirtual(orderedInvokerType, "addPendingMethod", null, params);

                        // Since asynchronous method was not executed, caller
                        // can read next request. This reduces the build up of
                        // threads caused by asynchronous methods.
                        b.loadConstant
                            (noPipeParam ? Skeleton.READ_ANY_THREAD : Skeleton.READ_FINISHED);
                        b.returnValue(TypeDesc.INT);

                        isNext.setLocation();

                        if (noPipeParam) {
                            // Call finished method before invocation.
                            genFinishedAsync(b, channelVar);
                        }
                    }
                }

                // Try to invoke the server side method.
                invokeMethod(b, method);

                tryEnd = b.createLabel().setLocation();

                if (method.isOrdered()) {
                    TypeDesc orderedInvokerType = TypeDesc.forClass(OrderedInvoker.class);
                    b.loadThis();
                    b.loadField(ORDERED_INVOKER_FIELD_NAME, orderedInvokerType);
                    b.loadLocal(sequenceVar);
                    b.invokeVirtual(orderedInvokerType, "finished", null,
                                    new TypeDesc[] {TypeDesc.INT});
                }
            }

            if (batchedRemote) {
                // Branch past exception handler if method did not throw one.
                Label haveRemote = b.createLabel();
                b.branch(haveRemote);

                TypeDesc rootRemoteType = TypeDesc.forClass(Remote.class);

                // Due to exception, actual remote object does not
                // exist. Create one that throws cause from every method.
                genExceptionHandler(b, tryStart, tryEnd, sequenceVar);
                LocalVariable exVar = b.createLocalVariable(null, THROWABLE_TYPE);
                b.storeLocal(exVar);
                b.loadThis();
                b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
                b.loadConstant(returnDesc);
                b.loadLocal(exVar);
                b.invokeInterface(SKEL_SUPPORT_TYPE, "failedBatchedRemote", rootRemoteType,
                                  new TypeDesc[] {CLASS_TYPE, THROWABLE_TYPE});
                Label linkRemote = b.createLabel();
                b.branch(linkRemote);

                haveRemote.setLocation();
                b.loadNull();
                b.storeLocal(exVar);

                linkRemote.setLocation();

                // Link remote object to id for batched method.
                LocalVariable remoteVar = b.createLocalVariable(null, returnDesc);
                b.storeLocal(remoteVar);

                b.loadThis();
                b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
                b.loadThis();
                b.loadConstant(method.getName());
                b.loadLocal(remoteTypeIdVar);
                b.loadLocal(remoteIdVar);
                b.loadConstant(returnDesc);
                b.loadLocal(remoteVar);
                b.invokeInterface(SKEL_SUPPORT_TYPE, "linkBatchedRemote", null,
                                  new TypeDesc[] {
                                      TypeDesc.forClass(Skeleton.class), TypeDesc.STRING,
                                      remoteTypeIdVar.getType(),
                                      remoteIdVar.getType(),
                                      CLASS_TYPE, rootRemoteType
                                  });

                b.loadLocal(exVar);
                Label hasException = b.createLabel();
                b.ifNullBranch(hasException, false);

                // Next batch request must be handled in same thread.
                b.loadConstant(Skeleton.READ_SAME_THREAD);
                b.returnValue(TypeDesc.INT);

                // Throw exception, to stop all remaining batch requests.
                hasException.setLocation();
                b.loadLocal(exVar);
                b.invokeStatic(BATCH_INV_EX_TYPE, "make", BATCH_INV_EX_TYPE,
                               new TypeDesc[] {THROWABLE_TYPE});
                b.throwObject();
            } else if (method.isBatched()) {
                if (completionVar == null) {
                    genDiscardResponse(b, returnDesc);
                } else {
                    genCompletionResponse
                        (b, returnDesc, completionVar, tryStart, tryEnd, sequenceVar);
                }

                // Next batch request must be handled in same thread.
                b.loadConstant(Skeleton.READ_SAME_THREAD);
                b.returnValue(TypeDesc.INT);

                if (completionVar == null) {
                    genExceptionHandler(b, tryStart, tryEnd, sequenceVar);
                    b.invokeStatic(BATCH_INV_EX_TYPE, "make", BATCH_INV_EX_TYPE,
                                   new TypeDesc[] {THROWABLE_TYPE});
                    b.throwObject();
                }
            } else if (method.isAsynchronous()) {
                if (completionVar == null) {
                    genDiscardResponse(b, returnDesc);
                } else {
                    genCompletionResponse
                        (b, returnDesc, completionVar, tryStart, tryEnd, sequenceVar);
                }

                b.loadConstant(Skeleton.READ_FINISHED);
                b.returnValue(TypeDesc.INT);

                if (completionVar == null) {
                    genExceptionHandler(b, tryStart, tryEnd, sequenceVar);
                    LocalVariable exVar = b.createLocalVariable(null, THROWABLE_TYPE);
                    b.storeLocal(exVar);
                    b.loadThis();
                    b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
                    b.loadLocal(exVar);
                    b.invokeInterface(SKEL_SUPPORT_TYPE, "uncaughtException", null,
                                      new TypeDesc[] {THROWABLE_TYPE});
                    b.loadConstant(Skeleton.READ_FINISHED);
                    b.returnValue(TypeDesc.INT);
                }
            } else { // synchronous method
                // For synchronous method, write response and flush stream.

                LocalVariable retVar = null;
                if (returnDesc != null) {
                    retVar = b.createLocalVariable(null, returnDesc);
                    b.storeLocal(retVar);
                }

                b.loadLocal(channelVar);
                b.invokeInterface(INV_CHANNEL_TYPE, "getOutputStream", INV_OUT_TYPE, null);
                LocalVariable invOutVar = b.createLocalVariable(null, INV_OUT_TYPE);
                b.storeLocal(invOutVar);

                b.loadLocal(invOutVar);
                b.loadNull();
                b.invokeVirtual(INV_OUT_TYPE, "writeThrowable", null,
                                new TypeDesc[] {THROWABLE_TYPE});

                boolean doReset;
                if (retVar == null) {
                    doReset = false;
                } else {
                    doReset = writeParam(b, method.getReturnType(), invOutVar, retVar);
                }

                // Call finished method.
                genFinished(b, channelVar, doReset);
                b.returnValue(TypeDesc.INT);

                genExceptionHandler(b, tryStart, tryEnd, sequenceVar);
                LocalVariable throwableVar = b.createLocalVariable(null, THROWABLE_TYPE);
                b.storeLocal(throwableVar);

                b.loadThis();
                b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
                b.loadLocal(channelVar);
                b.loadLocal(throwableVar);
                b.invokeInterface(SKEL_SUPPORT_TYPE, "finished", TypeDesc.INT,
                                  new TypeDesc[] {INV_CHANNEL_TYPE, THROWABLE_TYPE});
                b.returnValue(TypeDesc.INT);
            }
        }

        // For default case, throw a NoSuchMethodException.
        defaultLabel.setLocation();
        invokeBuilder.newObject(NO_SUCH_METHOD_EX_TYPE);
        invokeBuilder.dup();
        // Load methodId.
        invokeBuilder.loadLocal(invokeBuilder.getParameter(1));
        invokeBuilder.invokeStatic(TypeDesc.STRING, "valueOf",
                                   TypeDesc.STRING, new TypeDesc[] {TypeDesc.INT});
        invokeBuilder.invokeConstructor(NO_SUCH_METHOD_EX_TYPE, new TypeDesc[] {TypeDesc.STRING});
        invokeBuilder.throwObject();

        if (disposerGotoLabel != null) {
            disposerGotoLabel.setLocation();
            invokeBuilder.loadThis();
            invokeBuilder.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
            invokeBuilder.loadThis();
            invokeBuilder.loadField(ID_FIELD_NAME, VERSIONED_IDENTIFIER_TYPE);
            invokeBuilder.invokeInterface(SKEL_SUPPORT_TYPE, "dispose", null,
                                          new TypeDesc[] {VERSIONED_IDENTIFIER_TYPE});
            invokeBuilder.returnValue(TypeDesc.INT);
        }

        if (disposerTryLabels != null) {
            for (int i=0; i<disposerTryLabels.size(); i+=2) {
                invokeBuilder.exceptionHandler(disposerTryLabels.get(i),
                                               disposerTryLabels.get(i + 1),
                                               null);
            }
            invokeBuilder.loadThis();
            invokeBuilder.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
            invokeBuilder.loadThis();
            invokeBuilder.loadField(ID_FIELD_NAME, VERSIONED_IDENTIFIER_TYPE);
            invokeBuilder.invokeInterface(SKEL_SUPPORT_TYPE, "dispose", null,
                                          new TypeDesc[] {VERSIONED_IDENTIFIER_TYPE});
            invokeBuilder.throwObject();
        }

        // Add the Unreferenced.unreferenced method.
        {
            CodeBuilder b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "unreferenced", null, null));
            b.loadThis();
            b.loadField(REMOTE_FIELD_NAME, remoteType);
            b.instanceOf(UNREFERENCED_TYPE);
            Label notUnref = b.createLabel();
            b.ifZeroComparisonBranch(notUnref, "==");
            b.loadThis();
            b.loadField(REMOTE_FIELD_NAME, remoteType);
            b.checkCast(UNREFERENCED_TYPE);
            b.invokeInterface(UNREFERENCED_TYPE, "unreferenced", null, null);
            notUnref.setLocation();
            b.returnVoid();
        }

        // Add constructor.
        {
            CodeBuilder ctorBuilder;

            MethodInfo mi = cf.addConstructor
                (Modifiers.PUBLIC, new TypeDesc[] {
                    VERSIONED_IDENTIFIER_TYPE, SKEL_SUPPORT_TYPE, remoteType});

            ctorBuilder = new CodeBuilder(mi);

            ctorBuilder.loadThis();
            ctorBuilder.invokeSuperConstructor(null);

            if (hasDisposer) {
                cf.addField(Modifiers.PRIVATE.toFinal(true),
                            ID_FIELD_NAME, VERSIONED_IDENTIFIER_TYPE);
                ctorBuilder.loadThis();
                ctorBuilder.loadLocal(ctorBuilder.getParameter(0));
                ctorBuilder.storeField(ID_FIELD_NAME, VERSIONED_IDENTIFIER_TYPE);
            }

            ctorBuilder.loadThis();
            ctorBuilder.loadLocal(ctorBuilder.getParameter(1));
            ctorBuilder.storeField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
            ctorBuilder.loadThis();
            ctorBuilder.loadLocal(ctorBuilder.getParameter(2));
            ctorBuilder.storeField(REMOTE_FIELD_NAME, remoteType);

            if (hasOrderedMethods) {
                TypeDesc orderedInvokerType = TypeDesc.forClass(OrderedInvoker.class);

                cf.addField(Modifiers.PRIVATE.toFinal(true),
                            ORDERED_INVOKER_FIELD_NAME, orderedInvokerType);

                ctorBuilder.loadThis();
                ctorBuilder.loadLocal(ctorBuilder.getParameter(1));
                ctorBuilder.invokeInterface(SKEL_SUPPORT_TYPE, "createOrderedInvoker",
                                            orderedInvokerType, null);
                ctorBuilder.storeField(ORDERED_INVOKER_FIELD_NAME, orderedInvokerType);
            }

            ctorBuilder.returnVoid();
        }

        staticInitBuilder.returnVoid();

        return cf.defineClass();
    }

    private static String generateMethodName(Map<String, Integer> methodNames,
                                             RemoteMethod method)
    {
        String name = method.getName();
        while (true) {
            Integer count = methodNames.get(name);
            if (count == null) {
                methodNames.put(name, 1);
                return name;
            }
            methodNames.put(name, count + 1);
            name = name + '$' + count;
        }
    }

    /**
     * Creates local variable to hold invocation input stream, unless already
     * created. Assumes that 
     */
    private static LocalVariable invInVar(CodeBuilder b, LocalVariable channelVar,
                                          LocalVariable invInVar)
    {
        if (invInVar == null) {
            b.loadLocal(channelVar);
            b.invokeInterface(INV_CHANNEL_TYPE, "getInputStream", INV_IN_TYPE, null);
            invInVar = b.createLocalVariable(null, INV_IN_TYPE);
            b.storeLocal(invInVar);
        }
        return invInVar;
    }

    /**
     * @return return type of method, possibly null
     */
    private TypeDesc invokeMethod(CodeBuilder b, RemoteMethod method) {
        TypeDesc remoteType = TypeDesc.forClass(mType);
        if (methodExists(method)) {
            // Invoke the server side method.
            TypeDesc returnDesc = getTypeDesc(method.getReturnType());
            TypeDesc[] params = getTypeDescs(method.getParameterTypes());
            b.invokeInterface(remoteType, method.getName(), returnDesc, params);
            return returnDesc;
        } else if (mLocalInfo == null) {
            // Cannot invoke method because interface is malformed.
            TypeDesc exType = TypeDesc.forClass(MalformedRemoteObjectException.class);
            b.newObject(exType);
            b.dup();
            b.loadConstant(mMalformedInfoMessage);
            b.loadConstant(remoteType);
            b.invokeConstructor(exType, new TypeDesc[] {TypeDesc.STRING, CLASS_TYPE});
            b.throwObject();
            return null;
        } else {
            // Cannot invoke method because it is unimplemented.
            b.newObject(UNIMPLEMENTED_EX_TYPE);
            b.dup();
            b.loadConstant(method.getSignature());
            b.invokeConstructor
                (UNIMPLEMENTED_EX_TYPE, new TypeDesc[] {TypeDesc.STRING});
            b.throwObject();
            return null;
        }
    }

    private boolean methodExists(RemoteMethod method) {
        if (mLocalInfo == mInfo) {
            // Since method came from same info, of course it exists.
            return true;
        }

        if (mLocalInfo == null) {
            // Local interface is malformed and so no skeleton methods can be
            // implemented.
            return false;
        }

        List<? extends RemoteParameter> paramList = method.getParameterTypes();
        RemoteParameter[] paramTypes = new RemoteParameter[paramList.size()];
        paramList.toArray(paramTypes);

        try {
            RemoteMethod localMethod = mLocalInfo.getRemoteMethod(method.getName(), paramTypes);
            return equalTypes(localMethod.getReturnType(), method.getReturnType());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // Implementation leaves a boolean on the stack.
    private void genFinished(CodeBuilder b, LocalVariable channelVar, boolean reset) {
        b.loadThis();
        b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
        b.loadLocal(channelVar);
        b.loadConstant(reset);
        b.invokeInterface(SKEL_SUPPORT_TYPE, "finished", TypeDesc.INT,
                          new TypeDesc[] {INV_CHANNEL_TYPE, TypeDesc.BOOLEAN});
    }

    private void genFinishedAsync(CodeBuilder b, LocalVariable channelVar) {
        b.loadThis();
        b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
        b.loadLocal(channelVar);
        b.invokeInterface(SKEL_SUPPORT_TYPE, "finishedAsync", null,
                          new TypeDesc[] {INV_CHANNEL_TYPE});
    }

    private void genWaitForNext(CodeBuilder b, LocalVariable sequenceVar) {
        TypeDesc orderedInvokerType = TypeDesc.forClass(OrderedInvoker.class);
        b.loadThis();
        b.loadField(ORDERED_INVOKER_FIELD_NAME, orderedInvokerType);
        b.loadLocal(sequenceVar);
        b.invokeVirtual(orderedInvokerType, "waitForNext", null,
                        new TypeDesc[] {TypeDesc.INT});
    }

    // Implementation leaves a boolean on the stack.
    private void genIsNext(CodeBuilder b, LocalVariable sequenceVar) {
        TypeDesc orderedInvokerType = TypeDesc.forClass(OrderedInvoker.class);
        b.loadThis();
        b.loadField(ORDERED_INVOKER_FIELD_NAME, orderedInvokerType);
        b.loadLocal(sequenceVar);
        b.invokeVirtual(orderedInvokerType, "isNext", TypeDesc.BOOLEAN,
                        new TypeDesc[] {TypeDesc.INT});
    }

    /**
     * @param sequenceVar if non-null, calls OrderedInvoker.finished.
     */
    private void genExceptionHandler(CodeBuilder b, Label tryStart, Label tryEnd,
                                     LocalVariable sequenceVar)
    {
        b.exceptionHandler(tryStart, tryEnd, Throwable.class.getName());
        if (sequenceVar != null) {
            TypeDesc orderedInvokerType = TypeDesc.forClass(OrderedInvoker.class);
            b.loadThis();
            b.loadField(ORDERED_INVOKER_FIELD_NAME, orderedInvokerType);
            b.loadLocal(sequenceVar);
            b.invokeVirtual(orderedInvokerType, "finished", null, new TypeDesc[] {TypeDesc.INT});
        }
    }

    private void genDiscardResponse(CodeBuilder b, TypeDesc type) {
        if (type != null) {
            if (type.isDoubleWord()) {
                b.pop2();
            } else {
                b.pop();
            }
        }
    }

    private void genCompletionResponse(CodeBuilder b, TypeDesc type,
                                       LocalVariable completionVar,
                                       Label tryStart, Label tryEnd,
                                       LocalVariable sequenceVar)
    {
        TypeDesc remoteCompletionType = TypeDesc.forClass(RemoteCompletion.class);

        LocalVariable valueVar = b.createLocalVariable(null, type);
        b.storeLocal(valueVar);
        b.loadThis();
        b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
        b.loadLocal(valueVar);
        b.loadLocal(completionVar);
        b.invokeInterface(SKEL_SUPPORT_TYPE, "completion",
                          null, new TypeDesc[] {TypeDesc.forClass(Future.class),
                                                remoteCompletionType});

        Label skip = b.createLabel();
        b.branch(skip);
        genExceptionHandler(b, tryStart, tryEnd, sequenceVar);
        LocalVariable throwableVar = b.createLocalVariable(null, THROWABLE_TYPE);
        b.storeLocal(throwableVar);
        b.loadLocal(completionVar);
        b.loadLocal(throwableVar);
        b.invokeInterface(remoteCompletionType, "exception",
                          null, new TypeDesc[] {THROWABLE_TYPE});
        skip.setLocation();
    }

    private Class[] declaredExceptionTypes(RemoteMethod method) {
        Set<? extends RemoteParameter> all = method.getExceptionTypes();
        List<Class> declared = new ArrayList<Class>(all.size());

        for (RemoteParameter p : all) {
            Class type = p.getType();
            if (!RuntimeException.class.isAssignableFrom(type) &&
                !Error.class.isAssignableFrom(type))
            {
                declared.add(type);
            }
        }

        return declared.toArray(new Class[declared.size()]);
    }

    private static class Factory<R extends Remote> implements SkeletonFactory<R> {
        private final Constructor<? extends Skeleton> mSkeletonCtor;

        Factory(Constructor<? extends Skeleton> ctor) {
            mSkeletonCtor = ctor;
        }

        public Skeleton createSkeleton(VersionedIdentifier objId,
                                       SkeletonSupport support,
                                       R remoteServer)
        {
            Skeleton<R> skeleton;
            create: {
                Throwable error;
                try {
                    skeleton = mSkeletonCtor.newInstance(objId, support, remoteServer);
                    break create;
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

            if (remoteServer instanceof SessionAware) {
                final Skeleton<R> fSkeleton = skeleton;

                skeleton = new Skeleton<R>() {
                    @Override
                    public R getRemoteServer() {
                        return fSkeleton.getRemoteServer();
                    }

                    @Override
                    public int invoke(Link sessionLink, int methodId,
                                      InvocationChannel channel,
                                      BatchedInvocationException batchedException)
                    throws IOException, NoSuchMethodException, ClassNotFoundException,
                           BatchedInvocationException
                    {
                        LocalSession.THE.set(sessionLink);
                        try {
                            return fSkeleton.invoke
                                (sessionLink, methodId, channel, batchedException);
                        } finally {
                            LocalSession.THE.remove();
                        }
                    }

                    @Override
                    public void unreferenced() {
                        fSkeleton.unreferenced();
                    }
                };
            }

            return skeleton;
        }
    }
}
