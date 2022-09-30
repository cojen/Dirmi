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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.lang.reflect.UndeclaredThrowableException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Pipe;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class StubMaker {
    private static final SoftCache<TypeInfoKey, MethodHandle> cCache = new SoftCache<>();

    /**
     * Returns a new StubFactory instance.
     *
     * @param type non-null client-side remote interface to examine
     * @param typeId server-side type id
     * @param info server-side type information
     * @throws IllegalArgumentException if type is malformed
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    static StubFactory factoryFor(Class<?> type, long typeId, RemoteInfo info) {
        var key = new TypeInfoKey(type, info);
        var mh = cCache.get(key);
        if (mh == null) synchronized (cCache) {
            mh = cCache.get(key);
            if (mh == null) {
                mh = new StubMaker(type, info).finishFactory();
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

    private StubMaker(Class<?> type, RemoteInfo info) {
        mType = type;
        mClientInfo = RemoteInfo.examine(type);
        mServerInfo = info;

        String sourceFile = StubMaker.class.getSimpleName();

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
            .public_().extend(Stub.class).implement(type).final_().sourceFile(sourceFile);
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

        mm = mFactoryMaker.addMethod(Stub.class, "newStub", long.class, StubSupport.class);
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
     * Returns a class which has a constructor that accepts a StubSupport instance.
     *
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    private Class<?> finishStub() {
        {
            MethodMaker mm = mStubMaker.addConstructor
                (long.class, StubSupport.class, MethodIdWriter.class);
            mm.invokeSuperConstructor(mm.param(0), mm.param(1), mm.param(2));
        }

        Iterator<RemoteMethod> it1 = mClientInfo.remoteMethods().iterator();
        Iterator<RemoteMethod> it2 = mServerInfo.remoteMethods().iterator();

        RemoteMethod clientMethod = null;

        RemoteMethod serverMethod = null;
        int serverMethodId = -1;

        int batchedImmediateMethodId = -1;

        while (true) {
            if (clientMethod == null && it1.hasNext()) {
                clientMethod = it1.next();
            }

            if (serverMethod == null && it2.hasNext()) {
                serverMethod = it2.next();
                serverMethodId++;
            }

            if (clientMethod == null && serverMethod == null) {
                break;
            }

            Object returnType;
            String methodName;
            Object[] ptypes;

            int cmp;
            if (clientMethod == null) {
                // Only server-side methods remain.
                cmp = 1;
            } else if (serverMethod == null) {
                // Only client-side methods remain.
                cmp = -1;
            } else {
                cmp = clientMethod.compareTo(serverMethod);
            }

            if (cmp > 0) {
                // Attempt to implement a method that only exists on the server-side.
                try {
                    returnType = classForEx(serverMethod.returnType());
                    methodName = serverMethod.name();
                    List<String> pnames = serverMethod.parameterTypes();
                    ptypes = new Object[pnames.size()];
                    int i = 0;
                    for (String pname : pnames) {
                        ptypes[i++] = classForEx(pname);
                    }
                } catch (ClassNotFoundException e) {
                    // Can't be implemented, so skip it.
                    serverMethod = null;
                    continue;
                }
            } else {
                returnType = clientMethod.returnType();
                methodName = clientMethod.name();
                ptypes = clientMethod.parameterTypes().toArray(Object[]::new);
            }

            if (clientMethod != null && clientMethod.isBatchedImmediate() && cmp >= 0) {
                // No stub method is actually generated for this variant. The skeleton variant
                // is invoked when the remote typeId isn't known yet. RemoteMethod instances are
                // compared such that this variant comes immediately before the normal one.
                batchedImmediateMethodId = serverMethodId;
                clientMethod = null;
                serverMethod = null;
                continue;
            }

            MethodMaker mm = mStubMaker.addMethod(returnType, methodName, ptypes).public_();

            if (cmp < 0) {
                // The server doesn't implement the method.
                mm.new_(NoSuchMethodError.class, "Unimplemented on the remote side").throw_();
                clientMethod = null;
                continue;
            }

            Class<?> remoteFailureClass;
            List<Class<?>> thrownClasses = null;

            if (cmp > 0) {
                // Use the default failure exception for a method that only exists on the
                // server-side.
                remoteFailureClass = classFor(mClientInfo.remoteFailureException());
            } else {
                String remoteFailureName = clientMethod.remoteFailureException();
                remoteFailureClass = classFor(remoteFailureName);

                for (String ex : clientMethod.exceptionTypes()) {
                    Class<?> exClass = classFor(ex);
                    if (!CoreUtils.isUnchecked(exClass)) {
                        if (!remoteFailureClass.isAssignableFrom(exClass)) {
                            if (thrownClasses == null) {
                                thrownClasses = new ArrayList<>();
                            }
                            thrownClasses.add(exClass);
                            mm.throws_(exClass);
                        }
                    }
                }
            }

            mm.throws_(remoteFailureClass);

            if (clientMethod != null && clientMethod.isPiped() != serverMethod.isPiped()) {
                // Not expected.
                mm.new_(IncompatibleClassChangeError.class).throw_();
                clientMethod = null;
                serverMethod = null;
                continue;
            }

            var supportVar = mm.field("support").getAcquire();

            if (serverMethod.isDisposer()) {
                mm.field("support").setRelease(supportVar.invoke("dispose", mm.this_()));
            }

            Variable batchedPipeVar;
            Label unbatchedStart;
            if (serverMethod.isUnbatched()) {
                batchedPipeVar = supportVar.invoke("unbatch");
                unbatchedStart = mm.label().here();
            } else {
                batchedPipeVar = null;
                unbatchedStart = null;
            }

            var pipeVar = supportVar.invoke("connect", mm.this_(), remoteFailureClass);

            Label invokeStart = mm.label().here();
            Label invokeEnd = mm.label();

            pipeVar.invoke("writeLong", mm.field("id"));

            Variable thrownVar = null;
            Label throwException = null;

            Variable typeIdVar = null;
            Variable aliasIdVar = null;

            if (serverMethod.isBatched() && !isVoid(returnType)) {
                var returnClass = classFor(returnType);
                typeIdVar = supportVar.invoke("remoteTypeId", returnClass);
                aliasIdVar = supportVar.invoke("newAliasId");

                if (batchedImmediateMethodId >= 0) {
                    Label hasType = mm.label();
                    typeIdVar.ifNe(0L, hasType);

                    // Must call the immediate variant.

                    writeParams(mm, pipeVar, serverMethod, batchedImmediateMethodId, ptypes);
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

                    var returnVar = mm.var(returnType);
                    CoreUtils.readParam(pipeVar, returnVar);
                    Label done = mm.label();
                    isBatchingVar.ifTrue(done);
                    supportVar.invoke("batched", pipeVar);
                    done.here();
                    returnResult(mm, clientMethod,returnVar);

                    hasType.here();
                }
            }

            writeParams(mm, pipeVar, serverMethod, serverMethodId, ptypes);

            if (serverMethod.isPiped()) {
                supportVar.invoke("finishBatch", pipeVar).ifFalse(invokeEnd);
                pipeVar.invoke("flush");
                thrownVar = supportVar.invoke("readResponse", pipeVar);
                throwException = mm.label();
                thrownVar.ifNe(null, throwException);
                invokeEnd.here();
                mm.return_(pipeVar);
            } else if (serverMethod.isBatched()) {
                invokeEnd.here();
                supportVar.invoke("batched", pipeVar);
                if (isVoid(returnType)) {
                    mm.return_();
                } else {
                    pipeVar.invoke("writeLong", aliasIdVar);
                    var stubVar = supportVar.invoke
                        ("newAliasStub", remoteFailureClass, aliasIdVar, typeIdVar);
                    returnResult(mm, clientMethod, stubVar.cast(returnType));
                }
            } else {
                pipeVar.invoke("flush");

                Label noBatch = mm.label();
                supportVar.invoke("finishBatch", pipeVar).ifFalse(noBatch);
                thrownVar = supportVar.invoke("readResponse", pipeVar);
                throwException = mm.label();
                thrownVar.ifNe(null, throwException);
                noBatch.here();

                thrownVar.set(supportVar.invoke("readResponse", pipeVar));
                thrownVar.ifNe(null, throwException);

                if (isVoid(returnType)) {
                    invokeEnd.here();
                    supportVar.invoke("finished", pipeVar);
                    mm.return_();
                } else {
                    var returnVar = mm.var(returnType);
                    CoreUtils.readParam(pipeVar, returnVar);
                    invokeEnd.here();
                    supportVar.invoke("finished", pipeVar);
                    returnResult(mm, clientMethod, returnVar);
                }
            }

            var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
            exVar.set(supportVar.invoke("failed", remoteFailureClass, pipeVar, exVar));
            Label throwIt = mm.label().goto_();

            if (batchedPipeVar != null) {
                mm.finally_(unbatchedStart, () -> supportVar.invoke("rebatch", batchedPipeVar));
            }

            if (thrownVar != null) {
                throwException.here();
                supportVar.invoke("finished", pipeVar);
                exVar.set(thrownVar);
            }

            // Throw the exception as-is if declared or unchecked. Otherwise, wrap it.

            throwIt.here();
            Label reallyThrowIt = mm.label();
            List<Class<?>> toCheck = CoreUtils.reduceExceptions(remoteFailureClass, thrownClasses);
            for (Class<?> type : toCheck) {
                exVar.instanceOf(type).ifTrue(reallyThrowIt);
            }
            var msgVar = exVar.invoke("getMessage");
            exVar.set(mm.new_(UndeclaredThrowableException.class, exVar, msgVar));
            reallyThrowIt.here();
            exVar.throw_();

            if (cmp <= 0) {
                clientMethod = null;
            }
            serverMethod = null;
            batchedImmediateMethodId = -1;
        }

        return mStubMaker.finish();
    }

    private static boolean isVoid(Object returnType) {
        return returnType == void.class || returnType.equals("V");
    }

    private static void writeParams(MethodMaker mm, Variable pipeVar,
                                    RemoteMethod method, int methodId, Object[] ptypes)
    {
        mm.field("miw").getAcquire().invoke("writeMethodId", pipeVar, methodId);

        if (ptypes.length <= 0) {
            return;
        }

        // Write all of the non-pipe parameters.

        if (!method.isPiped()) {
            for (int i=0; i<ptypes.length; i++) {
                CoreUtils.writeParam(pipeVar, mm.param(i));
            }
        } else {
            String pipeDesc = Pipe.class.descriptorString();
            for (int i=0; i<ptypes.length; i++) {
                Object ptype = ptypes[i];
                if (ptype != Pipe.class && !ptype.equals(pipeDesc)) {
                    CoreUtils.writeParam(pipeVar, mm.param(i));
                }
            }
        }
    }

    private void returnResult(MethodMaker mm, RemoteMethod method, Variable resultVar) {
        if (method != null && method.isRestorable()) {
            // Unless already set, assign a fully bound MethodHandle instance to the inherited
            // origin field. No attempt is made to prevent multiple threads from assigning it,
            // because it won't affect the outcome.

            try {
                Class<?> returnType = classFor(method.returnType());

                List<String> pnames = method.parameterTypes();
                var ptypes = new Class[pnames.size()];
                int i = 0;
                for (String pname : pnames) {
                    ptypes[i++] = classFor(pname);
                }

                MethodType mt = MethodType.methodType(returnType, ptypes);

                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodHandle mh = lookup.findVirtual(mType, method.name(), mt);

                Label alreadySet = mm.label();
                Field originField = mm.access(Stub.cOriginHandle, resultVar.cast(Stub.class));
                originField.getAcquire().ifNe(null, alreadySet);

                var mhVar = mm.var(MethodHandle.class).setExact(mh);

                var paramVars = mm.new_(Object[].class, 1 + ptypes.length);
                paramVars.aset(0, mm.this_());
                for (i=0; i<ptypes.length; i++) {
                    paramVars.aset(i + 1, mm.param(i));
                }

                var methodHandlesVar = mm.var(MethodHandles.class);
                mhVar.set(methodHandlesVar.invoke("insertArguments", mhVar, 0, paramVars));

                originField.setRelease(mhVar);

                alreadySet.here();
            } catch (NoSuchMethodException | IllegalAccessException e) {
                // Not expected.
                throw new IllegalStateException(e);
            }
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
}
