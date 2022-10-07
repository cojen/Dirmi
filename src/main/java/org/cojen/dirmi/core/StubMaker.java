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
import java.util.List;

import org.cojen.maker.AnnotationMaker;
import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Restorable;
import org.cojen.dirmi.Unbatched;
import org.cojen.dirmi.UnimplementedException;

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

        var it = new JoinedIterator<>(mClientInfo.remoteMethods(), mServerInfo.remoteMethods());

        RemoteMethod lastServerMethod = null;
        int serverMethodId = -1;
        int batchedImmediateMethodId = -1;

        while (it.hasNext()) {
            JoinedIterator.Pair<RemoteMethod> pair = it.next();
            RemoteMethod clientMethod = pair.a;
            RemoteMethod serverMethod = pair.b;

            if (serverMethod != lastServerMethod && serverMethod != null) {
                serverMethodId++;
                lastServerMethod = serverMethod;
            }

            Object returnType;
            String methodName;
            Object[] ptypes;

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
            }

            MethodMaker mm = mStubMaker.addMethod(returnType, methodName, ptypes).public_();

            if (serverMethod == null) {
                // The server doesn't implement the method.
                mm.new_(UnimplementedException.class, "Unimplemented on the remote side").throw_();
                continue;
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
                        }
                    }
                }
            }

            mm.throws_(remoteFailureClass);

            if (clientMethod == null) {
                // Add annotations for methods which only exist on the server-side. This allows
                // a RemoteInfo instance to be created later without the server interface.

                if (serverMethod.isDisposer()) {
                    mm.addAnnotation(Disposer.class, true);
                }

                if (serverMethod.isBatched()) {
                    mm.addAnnotation(Batched.class, true);
                } else if (serverMethod.isUnbatched()) {
                    mm.addAnnotation(Unbatched.class, true);
                }

                if (serverMethod.isRestorable()) {
                    mm.addAnnotation(Restorable.class, true);
                }

                if (remoteFailureClass == RemoteException.class) {
                    if (serverMethod.isRemoteFailureExceptionUndeclared()) {
                        mm.addAnnotation(RemoteFailure.class, true).put("declared", false);
                    }
                } else {
                    AnnotationMaker am = mm.addAnnotation(RemoteFailure.class, true);
                    am.put("exception", remoteFailureClass);
                    if (serverMethod.isRemoteFailureExceptionUndeclared()) {
                        am.put("declared", false);
                    }
                }
            }

            if (clientMethod != null && clientMethod.isPiped() != serverMethod.isPiped()) {
                // Not expected.
                mm.new_(IncompatibleClassChangeError.class).throw_();
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
                    returnResult(mm, clientMethod, returnVar);

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

                if (!serverMethod.isNoReply()) {
                    thrownVar.set(supportVar.invoke("readResponse", pipeVar));
                    thrownVar.ifNe(null, throwException);
                }

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

            Label finished = mm.label();
            resultVar.ifEq(null, finished);

            Object[] originStub = {mm.this_()};
            Field originField = mm.access(Stub.cOriginHandle, originStub);
            Label parentHasOrigin = mm.label();
            originField.getAcquire().ifNe(null, parentHasOrigin);
            mm.new_(IllegalStateException.class,
                    "Cannot make a restorable object from a non-restorable parent").throw_();
            parentHasOrigin.here();

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

                originStub[0] = resultVar.cast(Stub.class);
                originField.getAcquire().ifNe(null, finished);

                var mhVar = mm.var(MethodHandle.class).setExact(mh);

                var paramVars = mm.new_(Object[].class, 1 + ptypes.length);
                paramVars.aset(0, mm.this_());
                for (i=0; i<ptypes.length; i++) {
                    paramVars.aset(i + 1, mm.param(i));
                }

                var methodHandlesVar = mm.var(MethodHandles.class);
                mhVar.set(methodHandlesVar.invoke("insertArguments", mhVar, 0, paramVars));

                originField.setRelease(mhVar);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                // Not expected.
                throw new IllegalStateException(e);
            }

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
}
