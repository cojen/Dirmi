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

import java.io.ObjectInputFilter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.lang.ref.Reference;

import java.lang.reflect.UndeclaredThrowableException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import org.cojen.maker.AnnotationMaker;
import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.Data;
import org.cojen.dirmi.DataUnavailableException;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Restorable;
import org.cojen.dirmi.Unbatched;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class StubMaker {
    private record TypeInfoKey(Class<?> type, RemoteInfo info) { }

    private static final SoftCache<TypeInfoKey, MethodHandle> cCache = new SoftCache<>();

    /**
     * Returns a new StubFactory instance.
     *
     * @param type non-null client-side remote interface to examine
     * @param typeId server-side type id
     * @param serverInfo server-side type information
     * @throws IllegalArgumentException if type is malformed
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    static StubFactory factoryFor(Class<?> type, long typeId, RemoteInfo serverInfo) {
        var key = new TypeInfoKey(type, serverInfo);
        var mh = cCache.get(key);
        if (mh == null) synchronized (cCache) {
            mh = cCache.get(key);
            if (mh == null) {
                mh = new StubMaker(type, serverInfo).finishFactory();
                cCache.put(key, mh);
            }
        }

        try {
            return (StubFactory) mh.invoke(typeId);
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private final Class<?> mType;
    private final RemoteInfo mClientInfo;
    private final RemoteInfo mServerInfo;
    private final ClassMaker mFactoryMaker;
    private final ClassMaker mStubMaker;
    private final ClassMaker mWrapperMaker;

    // Is set by the addStubMethods method if there are any data methods.
    private ClassMaker mContainerMaker;

    private StubMaker(Class<?> type, RemoteInfo serverInfo) {
        mType = type;
        mClientInfo = RemoteInfo.examine(type);
        mServerInfo = serverInfo;

        String sourceFile = getClass().getSimpleName();

        Class<?> superClass;
        {
            int numMethods = mServerInfo.remoteMethods().size();
            if (numMethods < 256) {
                superClass = StubFactory.BW.class;
            } else if (numMethods < 65536) {
                superClass = StubFactory.SW.class;
            } else {
                // Impossible case.
                superClass = StubFactory.IW.class;
            }
        }

        mFactoryMaker = ClassMaker.begin(type.getName(), type.getClassLoader(), CoreUtils.MAKER_KEY)
            .extend(superClass).final_().sourceFile(sourceFile);
        CoreUtils.allowAccess(mFactoryMaker);

        mStubMaker = mFactoryMaker.another(type.getName())
            .public_().implement(type).final_().sourceFile(sourceFile);

        if (!serverInfo.isAutoDispose()) {
            mStubMaker.extend(StubInvoker.class);
            mWrapperMaker = null;
        } else {
            mWrapperMaker = mFactoryMaker.another(type.getName())
                .public_().extend(StubWrapper.class).implement(type)
                .final_().sourceFile(sourceFile);

            boolean needsCountedRef = false;

            for (RemoteMethod m : mServerInfo.remoteMethods()) {
                if (m.isUnacknowledged()) {
                    needsCountedRef = true;
                    break;
                }
            }

            mStubMaker.extend(needsCountedRef ? StubInvoker.WithCountedRef.class
                              : StubInvoker.WithBasicRef.class);
        }
    }

    /**
     * @return a StubFactory constructor which accepts a typeId
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    private MethodHandle finishFactory() {
        finishStub();

        MethodMaker mm = mFactoryMaker.addConstructor(long.class);
        mm.invokeSuperConstructor(mm.param(0));

        mm = mFactoryMaker.addMethod(StubInvoker.class, "newStub", long.class, StubSupport.class);
        mm.public_().return_(mm.new_(mStubMaker, mm.param(0), mm.param(1), mm.this_()));

        MethodHandles.Lookup lookup = mFactoryMaker.finishLookup();

        try {
            return lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class, long.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Returns a StubInvoker subclass which can be constructed with:
     *
     *   (long id, StubSupport support, MethodIdWriter miw)
     *
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    private Class<?> finishStub() {
        StubWrapper.Factory wrapperFactory;

        MethodMaker ctor = mStubMaker.addConstructor
            (long.class, StubSupport.class, MethodIdWriter.class);

        if (mWrapperMaker == null) {
            ctor.invokeSuperConstructor(ctor.param(0), ctor.param(1), ctor.param(2));
            wrapperFactory = null;
        } else {
            // Cannot call init until the wrapper class is defined, but it cannot be defined
            // until the invoker is defined. There's a cyclic dependency.
            wrapperFactory = new StubWrapper.Factory();
            var factoryVar = ctor.var(StubWrapper.Factory.class).setExact(wrapperFactory);
            ctor.invokeSuperConstructor(ctor.param(0), ctor.param(1), ctor.param(2), factoryVar);

            MethodMaker wrapperCtor = mWrapperMaker.addConstructor(mStubMaker);
            wrapperCtor.invokeSuperConstructor(wrapperCtor.param(0));
        }

        addStubMethods();

        if (mContainerMaker != null) {
            Class<?> containerClass = finishContainer();

            ctor.field("data").setRelease(ctor.new_(containerClass));

            addFactoryPipeDataMethod("readDataAndUnlatch");
            addFactoryPipeDataMethod("readOrSkipData");

            {
                MethodMaker mm = mFactoryMaker.addMethod
                    (DataContainer.class, "getData", StubInvoker.class).protected_().override();
                var stubVar = mm.param(0).cast(mStubMaker);
                mm.return_(stubVar.field("data").getAcquire().cast(DataContainer.class));
            }

            {
                MethodMaker mm = mFactoryMaker.addMethod
                    (null, "setData", StubInvoker.class, DataContainer.class)
                    .protected_().override();

                var stubVar = mm.param(0).cast(mStubMaker);
                var dataVar = mm.param(1);

                dataVar.instanceOf(containerClass).ifTrue(() -> {
                    stubVar.field("data").setRelease(dataVar.cast(containerClass));
                }, () -> {
                    // The DataContainer type has changed, and so the data is unavailable. This
                    // case isn't expected, but handle it as best as possible.
                    var uDataVar = mm.new_(containerClass, "mismatch");
                    stubVar.field("data").setRelease(uDataVar);
                });
            }
        }

        Class<?> stubClass = mStubMaker.finish();

        if (mWrapperMaker != null) {
            MethodHandles.Lookup lookup = mWrapperMaker.finishHidden();
            try {
                wrapperFactory.init
                    (lookup.findConstructor
                     (lookup.lookupClass(), MethodType.methodType(void.class, stubClass)));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        return stubClass;
    }

    private void addFactoryPipeDataMethod(String which) {
        MethodMaker mm = mFactoryMaker.addMethod
            (null, which, StubInvoker.class, Pipe.class).protected_().override();
        var stubVar = mm.param(0).cast(mStubMaker);
        stubVar.field("data").getAcquire().cast(DataContainer.class).invoke(which, mm.param(1));
    }

    private void addStubMethods() {
        var it = new JoinedIterator<>(mClientInfo.remoteMethods(), mServerInfo.remoteMethods());

        RemoteMethod lastServerMethod = null;
        int serverMethodId = -1;
        int batchedImmediateMethodId = -1;
        int syntheticMethodId = mServerInfo.remoteMethods().size();

        while (it.hasNext()) {
            JoinedIterator.Pair<RemoteMethod> pair = it.next();
            RemoteMethod clientMethod = pair.a;
            RemoteMethod serverMethod = pair.b;

            if (serverMethod != lastServerMethod
                && serverMethod != null && !serverMethod.isData())
            {
                serverMethodId++;
                lastServerMethod = serverMethod;
            }

            final Object returnType;
            final String methodName;
            final Object[] ptypes;
            final boolean isData;

            if (serverMethod != null && serverMethod.isBatchedImmediate()) {
                // No stub method is actually generated for this variant. The skeleton variant
                // is invoked when the remote typeId isn't known yet. RemoteMethod instances
                // are compared such that this variant comes immediately before the normal one.
                batchedImmediateMethodId = serverMethodId;
                continue;
            }

            if (clientMethod == null) {
                // Attempt to implement a method that only exists on the server-side.
                try {
                    Class<?> returnClass = classForEx(serverMethod.returnType());
                    if (serverMethod.isSerialized() && !returnClass.isPrimitive()) {
                        // Client doesn't have an object input filter, so skip the method.
                        continue;
                    }
                    methodName = serverMethod.name();
                    List<String> pnames = serverMethod.parameterTypes();
                    ptypes = new Object[pnames.size()];
                    int i = 0;
                    for (String pname : pnames) {
                        ptypes[i++] = classForEx(pname);
                    }
                    returnType = returnClass;
                    isData = serverMethod.isData();
                } catch (ClassNotFoundException e) {
                    // Can't be implemented, so skip it.
                    continue;
                }
            } else {
                if (clientMethod.isBatchedImmediate()) {
                    // No stub method is actually generated for this variant.
                    continue;
                }
                returnType = clientMethod.returnType();
                methodName = clientMethod.name();
                ptypes = clientMethod.parameterTypes().toArray(Object[]::new);
                isData = clientMethod.isData();
            }

            MethodMaker mm = mStubMaker.addMethod(returnType, methodName, ptypes).public_();

            MethodMaker mm2;
            if (mWrapperMaker == null) {
                mm2 = null;
            } else {
                // Delegate to the actual invoker.
                mm2 = mWrapperMaker.addMethod(returnType, methodName, ptypes).public_();

                Label start = mm2.label().here();

                var params = new Object[ptypes.length];
                for (int i=0; i<params.length; i++) {
                    params[i] = mm2.param(i);
                }

                Object result = mm2.field("invoker").cast(mStubMaker).invoke(methodName, params);

                if (isVoid(returnType)) {
                    mm2.return_();
                } else {
                    mm2.return_(result);
                }

                // Prevent the weakly referenced wrapper from being GC'd too soon.
                mm2.finally_(start, () -> {
                    mm2.var(Reference.class).invoke("reachabilityFence", mm2.this_());
                });
            }

            Class<?> remoteFailureClass;
            List<Class<?>> thrownClasses = null;

            if (clientMethod == null) {
                try {
                    remoteFailureClass = classForEx(serverMethod.remoteFailureException());
                } catch (ClassNotFoundException e) {
                    // Use the default failure exception instead.
                    remoteFailureClass = classFor(mClientInfo.remoteFailureException());
                }
            } else {
                remoteFailureClass = classFor(clientMethod.remoteFailureException());

                for (String ex : clientMethod.exceptionTypes()) {
                    Class<?> exClass = classFor(ex);
                    if (!CoreUtils.isUnchecked(exClass)) {
                        if (!remoteFailureClass.isAssignableFrom(exClass)) {
                            if (thrownClasses == null) {
                                thrownClasses = new ArrayList<>();
                            }
                            thrownClasses.add(exClass);
                            mm.throws_(exClass);
                            if (mm2 != null) {
                                mm2.throws_(exClass);
                            }
                        }
                    }
                }
            }

            mm.throws_(remoteFailureClass);
            if (mm2 != null) {
                mm2.throws_(remoteFailureClass);
            }

            RemoteMethod method = serverMethod;
            int methodId = serverMethodId;

            if (method == null) {
                // If the server method isn't implemented, use the client definition as a best
                // guess for now.
                method = clientMethod;

                mm.addAnnotation(Unimplemented.class, true);

                if (method.isBatched()) {
                    // Create a gap to make room for the batched immediate variant.
                    batchedImmediateMethodId = syntheticMethodId++;
                }

                methodId = syntheticMethodId++;
            }

            {
                // Repeat all the necessary annotations inherited by the remote interface. This
                // allows RemoteInfo.examineStub to work properly without consulting the remote
                // interface, which might not have all the server-side methods anyhow.

                if (method.isData()) {
                    mm.addAnnotation(Data.class, true);
                }

                if (method.isDisposer()) {
                    mm.addAnnotation(Disposer.class, true);
                }

                if (method.isBatched()) {
                    mm.addAnnotation(Batched.class, true);
                } else if (method.isUnbatched()) {
                    mm.addAnnotation(Unbatched.class, true);
                }

                if (isClientMethodRestorable(clientMethod)) {
                    AnnotationMaker am = mm.addAnnotation(Restorable.class, true);
                    if (clientMethod.isLenient()) {
                        am.put("lenient", true);
                    }
                }

                if (remoteFailureClass == RemoteException.class) {
                    if (method.isRemoteFailureExceptionUndeclared()) {
                        mm.addAnnotation(RemoteFailure.class, true).put("declared", false);
                    }
                } else {
                    AnnotationMaker am = mm.addAnnotation(RemoteFailure.class, true);
                    am.put("exception", remoteFailureClass);
                    if (method.isRemoteFailureExceptionUndeclared()) {
                        am.put("declared", false);
                    }
                }
            }

            if (clientMethod != null && clientMethod.isPiped() != method.isPiped()) {
                // Not expected.
                mm.new_(IncompatibleClassChangeError.class).throw_();
                continue;
            }

            if (isData) {
                if (mContainerMaker == null) {
                    mContainerMaker = mFactoryMaker.another(mType.getName())
                        .extend(DataContainer.class).implement(mType).final_()
                        .sourceFile(getClass().getSimpleName());

                    mStubMaker.addField(mType, "data");
                }

                var resultVar = mm.field("data").getAcquire().invoke(methodName);
                if (resultVar != null) {
                    mm.return_(resultVar);
                }

                continue;
            }

            Label obtainSupport = mm.label().here();
            var supportVar = mm.field("support").getAcquire();

            Label connectStart = method.isDisposer() ? mm.label().here() : null;

            String connectMethod;
            if (isClientMethodRestorableLenient(clientMethod)) {
                connectMethod = method.isUnbatched() ? "tryConnectUnbatched" : "tryConnect";
            } else {
                connectMethod = method.isUnbatched() ? "connectUnbatched" : "connect";
            }

            var pipeVar = supportVar.invoke(connectMethod, mm.this_(), remoteFailureClass);

            if (method.isDisposer()) {
                // If cannot connect, this stub should still be disposed as a side-effect.
                mm.catch_(connectStart, Throwable.class, exVar -> {
                    supportVar.invoke("dispose", mm.this_());
                    exVar.throw_();
                });
            }

            supportVar.invoke("validate", mm.this_(), pipeVar).ifFalse(obtainSupport);

            Variable returnVar = isVoid(returnType) ? null : mm.var(returnType);
            Label hasResult = null;

            if (returnVar != null && isClientMethodRestorableLenient(clientMethod)) {
                Label connected = mm.label();
                pipeVar.ifNe(null, connected);
                returnVar.set(supportVar.invoke("newDisconnectedStub", returnVar.classType(), null)
                              .cast(returnType));
                hasResult = mm.label().goto_();
                connected.here();
            }

            if (method.isDisposer()) {
                supportVar.invoke("dispose", mm.this_());
            }

            Label invokeStart = mm.label().here();
            Label invokeEnd = mm.label();

            if (mWrapperMaker != null && method.isUnacknowledged()) {
                // The wrapper cannot be GC'd until after the server has replied back with
                // decRefCount, indicating acknowledgment. If there's an IOException writing
                // over the pipe, then auto disposal won't work, but it doesn't need to. The
                // session will close, effectively disposing the stub.
                mm.field("ref").invoke("incRefCount");
            }

            pipeVar.invoke("writeLong", mm.field("id"));

            Variable thrownVar = null;
            Label throwException = null;

            Variable typeIdVar = null;
            Variable aliasIdVar = null;

            if (method.isBatched() && returnVar != null) {
                var returnClass = classFor(returnType);
                typeIdVar = supportVar.invoke("remoteTypeId", returnClass);
                aliasIdVar = supportVar.invoke("newAliasId");

                if (batchedImmediateMethodId >= 0) {
                    Label hasType = mm.label();
                    typeIdVar.ifNe(0L, hasType);

                    // Must call the immediate variant.

                    writeParams(mm, pipeVar, method, batchedImmediateMethodId, ptypes);
                    pipeVar.invoke("writeLong", aliasIdVar);
                    pipeVar.invoke("flush");

                    Label noBatch = mm.label();
                    var isBatchingVar = supportVar.invoke("isBatching", pipeVar);
                    isBatchingVar.ifFalse(noBatch);
                    thrownVar = supportVar.invoke("readResponse", pipeVar);
                    throwException = mm.label();
                    thrownVar.ifNe(null, throwException);
                    noBatch.here();

                    thrownVar.set(supportVar.invoke("readResponse", pipeVar));
                    thrownVar.ifNe(null, throwException);

                    CoreUtils.readParam(pipeVar, returnVar);
                    Label done = mm.label();
                    isBatchingVar.ifTrue(done);
                    supportVar.invoke("batched", pipeVar);
                    done.here();
                    returnResult(mm, clientMethod, hasResult, returnVar);

                    hasType.here();
                }
            }

            writeParams(mm, pipeVar, method, methodId, ptypes);

            if (method.isPiped()) {
                supportVar.invoke("finishBatch", pipeVar).ifFalse(invokeEnd);
                pipeVar.invoke("flush");
                thrownVar = supportVar.invoke("readResponse", pipeVar);
                throwException = mm.label();
                thrownVar.ifNe(null, throwException);
                invokeEnd.here();
                mm.return_(pipeVar);
            } else if (method.isBatched()) {
                invokeEnd.here();
                supportVar.invoke("batched", pipeVar);
                if (returnVar == null) {
                    mm.return_();
                } else {
                    pipeVar.invoke("writeLong", aliasIdVar);
                    var stubVar = supportVar.invoke
                        ("newAliasStub", remoteFailureClass, aliasIdVar, typeIdVar);
                    returnVar.set(stubVar.cast(returnType));
                    returnResult(mm, clientMethod, hasResult, returnVar);
                }
            } else {
                pipeVar.invoke("flush");

                Label noBatch = mm.label();
                supportVar.invoke("finishBatch", pipeVar).ifFalse(noBatch);
                thrownVar = supportVar.invoke("readResponse", pipeVar);
                throwException = mm.label();
                thrownVar.ifNe(null, throwException);
                noBatch.here();

                if (!method.isNoReply()) {
                    thrownVar.set(supportVar.invoke("readResponse", pipeVar));
                    thrownVar.ifNe(null, throwException);
                }

                if (returnVar == null) {
                    invokeEnd.here();
                    supportVar.invoke("finished", pipeVar);
                    mm.return_();
                } else {
                    Variable inVar = pipeVar;
                    if (method.isSerialized() && CoreUtils.isObjectType(returnType)) {
                        inVar = mm.new_(CoreObjectInputStream.class, pipeVar);
                        ObjectInputFilter filter = clientMethod.objectInputFilter();
                        var filterVar = mm.var(ObjectInputFilter.class).setExact(filter);
                        inVar.invoke("setObjectInputFilter", filterVar);
                    }
                    CoreUtils.readParam(inVar, returnVar);
                    invokeEnd.here();
                    supportVar.invoke("finished", pipeVar);
                    returnResult(mm, clientMethod, hasResult, returnVar);
                }
            }

            var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);

            if (returnVar != null && isClientMethodRestorableLenient(clientMethod)) {
                returnVar.set(supportVar.invoke("newDisconnectedStub", returnVar.classType(), exVar)
                              .cast(returnType));
                hasResult.goto_();
            }

            supportVar.invoke("failed", remoteFailureClass, pipeVar, exVar).throw_();

            if (thrownVar != null) {
                throwException.here();
                supportVar.invoke("finished", pipeVar);
                throwException(thrownVar, remoteFailureClass, thrownClasses);
            }

            batchedImmediateMethodId = -1;
        }
    }

    /**
     * Throws an exception as-is if it's declared to be thrown, or if it's unchecked.
     * Otherwise, wrap it. This should only be used for exceptions which originated from the
     * remote side and weren't caused by a communication failure.
     *
     * @param exVar must be type Throwable
     * @param remoteFailureClass exception for reporting remote failures (usually RemoteException)
     * @param thrownClasses optional list of additional checked exceptions declared to be
     * thrown by the client method
     */
    private static void throwException(Variable exVar,
                                       Class<?> remoteFailureClass, List<Class<?>> thrownClasses)
    {
        MethodMaker mm = exVar.methodMaker();
        Label throwIt = mm.label();
        List<Class<?>> toCheck = CoreUtils.reduceExceptions(remoteFailureClass, thrownClasses);
        for (Class<?> type : toCheck) {
            exVar.instanceOf(type).ifTrue(throwIt);
        }
        var msgVar = exVar.invoke("getMessage");
        exVar.set(mm.new_(UndeclaredThrowableException.class, exVar, msgVar));
        throwIt.here();
        exVar.throw_();
    }

    private static boolean isVoid(Object returnType) {
        return returnType == void.class || returnType.equals("V");
    }

    private void writeParams(MethodMaker mm, Variable pipeVar,
                             RemoteMethod method, int methodId, Object[] ptypes)
    {
        String writeName = "writeMethodId";

        if (methodId >= mServerInfo.remoteMethods().size()) {
            writeName = "writeSyntheticMethodId";
        }

        mm.field("miw").getAcquire().invoke(writeName, pipeVar, methodId, method.name());

        if (ptypes.length <= 0) {
            return;
        }

        var outVar = pipeVar;

        if (method.isSerialized() && CoreUtils.anyObjectTypes(ptypes)) {
            outVar = mm.new_(CoreObjectOutputStream.class, pipeVar);
        }

        // Write all of the non-pipe parameters.

        if (!method.isPiped()) {
            for (int i=0; i<ptypes.length; i++) {
                CoreUtils.writeParam(outVar, mm.param(i));
            }
        } else {
            String pipeDesc = Pipe.class.descriptorString();
            for (int i=0; i<ptypes.length; i++) {
                Object ptype = ptypes[i];
                if (ptype != Pipe.class && !ptype.equals(pipeDesc)) {
                    CoreUtils.writeParam(outVar, mm.param(i));
                }
            }
        }

        if (outVar != pipeVar) {
            outVar.invoke("drain");
        }
    }

    private static boolean isClientMethodRestorable(RemoteMethod clientMethod) {
        return clientMethod != null && clientMethod.isRestorable();
    }

    private static boolean isClientMethodRestorableLenient(RemoteMethod clientMethod) {
        return isClientMethodRestorable(clientMethod) && clientMethod.isLenient();
    }

    private void returnResult(MethodMaker mm, RemoteMethod clientMethod,
                              Label hasResult, Variable resultVar)
    {
        if (isClientMethodRestorable(clientMethod)) {
            // Unless already set, assign a fully bound MethodHandle instance to the inherited
            // origin field. No attempt is made to prevent multiple threads from assigning it,
            // because it won't affect the outcome.

            Label finished = mm.label();
            resultVar.ifEq(null, finished);

            if (hasResult != null) {
                hasResult.here();
            }

            Object[] originStub = {mm.this_()};
            Field originField = mm.access(StubInvoker.cOriginHandle, originStub);
            Label parentHasOrigin = mm.label();
            originField.getAcquire().ifNe(null, parentHasOrigin);
            mm.new_(IllegalStateException.class,
                    "Cannot make a restorable object from a non-restorable parent").throw_();
            parentHasOrigin.here();

            originStub[0] = mm.var(CoreUtils.class).invoke("invoker", resultVar);
            originField.getAcquire().ifNe(null, finished);

            Class<?> returnType = classFor(clientMethod.returnType());

            List<String> pnames = clientMethod.parameterTypes();
            var ptypes = new Class[pnames.size()];
            int i = 0;
            for (String pname : pnames) {
                ptypes[i++] = classFor(pname);
            }

            MethodType mt = MethodType.methodType(returnType, ptypes);

            var mhVar = mm.var(MethodHandles.class).invoke("lookup")
                .invoke("findVirtual", mType, clientMethod.name(), mt);

            var paramVars = mm.new_(Object[].class, 1 + ptypes.length);
            paramVars.aset(0, mm.this_());
            for (i=0; i<ptypes.length; i++) {
                paramVars.aset(i + 1, mm.param(i));
            }

            var methodHandlesVar = mm.var(MethodHandles.class);
            mhVar = methodHandlesVar.invoke("insertArguments", mhVar, 0, paramVars);

            originField.setRelease(mhVar);

            finished.here();
        }

        mm.return_(resultVar);
    }

    private Class<?> classFor(Object obj) throws NoClassDefFoundError {
        return obj instanceof Class ? ((Class<?>) obj) : classFor((String) obj);
    }

    /**
     * @param name class name or descriptor
     */
    private Class<?> classFor(String name) throws NoClassDefFoundError {
        try {
            return classForEx(name);
        } catch (ClassNotFoundException e) {
            var error = new NoClassDefFoundError(name);
            error.initCause(e);
            throw error;
        }
    }

    /**
     * @param name class name or descriptor
     */
    private Class<?> classForEx(String name) throws ClassNotFoundException {
        return CoreUtils.loadClassByNameOrDescriptor(name, mStubMaker.classLoader());
    }

    private Class<?> finishContainer() {
        mContainerMaker.addConstructor().invokeSuperConstructor();

        {
            MethodMaker ctor = mContainerMaker.addConstructor(Object.class);
            ctor.invokeSuperConstructor(ctor.param(0));
        }

        var cmp = new DataMethodComparator();
        SortedSet<RemoteMethod> clientMethods = mClientInfo.dataMethods(cmp);
        SortedSet<RemoteMethod> serverMethods = mServerInfo.dataMethods(cmp);

        {
            MethodMaker mm = mContainerMaker.addMethod(null, "readDataAndUnlatch", Pipe.class)
                .protected_().override();
            var pipeVar = mm.param(0);

            pipeVar.ifEq(null, () -> {
                mm.invoke("dataUnavailable", mm.this_(), null);
                mm.return_();
            });

            Label tryStart = mm.label().here();

            var it = new JoinedIterator<>(clientMethods, serverMethods, cmp);
            Set<ObjectInputFilter> serializedFilters = null;

            while (it.hasNext()) {
                JoinedIterator.Pair<RemoteMethod> pair = it.next();
                RemoteMethod clientMethod = pair.a;
                RemoteMethod serverMethod = pair.b;

                if (serverMethod == null) {
                    String name = clientMethod.name();
                    mContainerMaker.addMethod
                        (clientMethod.returnType(), name).public_()
                        .new_(DataUnavailableException.class, name).throw_();
                    continue;
                }

                String type = serverMethod.returnType();

                if (clientMethod == null) {
                    CoreUtils.skipParam(pipeVar, null, mm.var(type).classType());
                    continue;
                }

                String name = serverMethod.name();
                MethodMaker elementMaker = mContainerMaker.addMethod(type, name).public_();

                if (isVoid(type)) {
                    continue;
                }

                mContainerMaker.addField(type, name).private_();
                elementMaker.invoke("awaitData", elementMaker.this_());
                elementMaker.return_(elementMaker.field(name));

                if (serverMethod.isSerializedReturnType()) {
                    if (serializedFilters == null) {
                        serializedFilters = new LinkedHashSet<>();
                    }
                    serializedFilters.add(clientMethod.objectInputFilter());
                    continue;
                }

                var dataVar = mm.var(type);
                CoreUtils.readParam(pipeVar, dataVar);
                mm.field(name).set(dataVar);
            }

            if (serializedFilters != null) {
                ObjectInputFilter fullFilter = null;
                for (ObjectInputFilter filter : serializedFilters) {
                    if (fullFilter == null) {
                        fullFilter = filter;
                    } else {
                        fullFilter = ObjectInputFilter.merge(fullFilter, filter);
                    }
                }

                var inVar = mm.new_(CoreObjectInputStream.class, pipeVar);
                var filterVar = mm.var(ObjectInputFilter.class).setExact(fullFilter);
                inVar.invoke("setObjectInputFilter", filterVar);
 
                it = new JoinedIterator<>(clientMethods, serverMethods, cmp);

                while (it.hasNext()) {
                    JoinedIterator.Pair<RemoteMethod> pair = it.next();
                    RemoteMethod clientMethod = pair.a;
                    RemoteMethod serverMethod = pair.b;

                    if (serverMethod == null || !serverMethod.isSerializedReturnType()) {
                        continue;
                    }

                    var dataVar = inVar.invoke("readObject");

                    if (clientMethod != null) {
                        String type = serverMethod.returnType();
                        if (type != null) {
                            dataVar = dataVar.cast(type);
                        }
                        mm.field(serverMethod.name()).set(dataVar);
                    }
                }
            }

            mm.catch_(tryStart, Throwable.class, exVar -> {
                mm.invoke("dataUnavailable", mm.this_(), exVar);
                exVar.throw_();
            });

            mm.invoke("dataAvailable", mm.this_());
        }

        {
            MethodMaker mm = mContainerMaker.addMethod(null, "readOrSkipData", Pipe.class)
                .protected_().override();
            var pipeVar = mm.param(0);

            Label doSkip = mm.label();

            mm.invoke("relatch", mm.this_()).ifFalse(doSkip);
            mm.invoke("readDataAndUnlatch", pipeVar);
            mm.return_();

            doSkip.here();

            boolean hasSerialized = false;
            int primitiveSize = 0;

            for (RemoteMethod m : serverMethods) {
                if (m.isSerializedReturnType()) {
                    hasSerialized = true;
                    continue;
                }

                String type = m.returnType();
                int size = CoreUtils.primitiveSize(type);

                if (size >= 0) {
                    primitiveSize += size;
                    continue;
                }

                if (primitiveSize > 0) {
                    pipeVar.invoke("skipNBytes", primitiveSize);
                    primitiveSize = 0;
                }

                CoreUtils.skipParam(pipeVar, null, mm.var(type).classType());
            }

            if (primitiveSize > 0) {
                pipeVar.invoke("skipNBytes", primitiveSize);
            }

            if (hasSerialized) {
                var inVar = mm.new_(CoreObjectInputStream.class, pipeVar);

                for (RemoteMethod m : serverMethods) {
                    if (m.isSerializedReturnType()) {
                        inVar.invoke("readObject");
                    }
                }
            }
        }

        return mContainerMaker.finishHidden().lookupClass();
    }
}
