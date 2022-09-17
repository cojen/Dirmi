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
import java.lang.invoke.VarHandle;

import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;

import org.cojen.maker.ClassMaker;
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

    private final RemoteInfo mClientInfo;
    private final RemoteInfo mServerInfo;
    private final ClassMaker mFactoryMaker;
    private final ClassMaker mStubMaker;

    private StubMaker(Class<?> type, RemoteInfo info) {
        mClientInfo = RemoteInfo.examine(type);
        mServerInfo = info;

        String sourceFile = StubMaker.class.getSimpleName();

        mFactoryMaker = ClassMaker.begin(type.getName(), type.getClassLoader(), CoreUtils.MAKER_KEY)
            .extend(StubFactory.class).final_().sourceFile(sourceFile);
        CoreUtils.allowAccess(mFactoryMaker);

        mStubMaker = mFactoryMaker.another(type.getName())
            .extend(Stub.class).implement(type).final_().sourceFile(sourceFile);
    }

    /**
     * @return a StubFactory constructor which accepts a typeId
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    private MethodHandle finishFactory() {
        Class<?> stubClass = finishStub();

        MethodMaker mm = mFactoryMaker.addConstructor(long.class);
        mm.invokeSuperConstructor(mm.param(0));

        mm = mFactoryMaker.addMethod(Stub.class, "newStub", long.class, StubSupport.class);
        mm.public_().return_(mm.new_(stubClass, mm.param(0), mm.param(1)));

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
            MethodMaker mm = mStubMaker.addConstructor(long.class, StubSupport.class);
            mm.invokeSuperConstructor(mm.param(0), mm.param(1));
        }

        SortedSet<RemoteMethod> clientMethods = mClientInfo.remoteMethods();
        SortedSet<RemoteMethod> serverMethods = mServerInfo.remoteMethods();

        int numMethods = serverMethods.size();

        Iterator<RemoteMethod> it1 = clientMethods.iterator();
        Iterator<RemoteMethod> it2 = serverMethods.iterator();

        RemoteMethod clientMethod = null;

        RemoteMethod serverMethod = null;
        int serverMethodId = -1;

        int batchedImmediateMethodId = -1;

        while (true) {
            if (clientMethod == null) {
                if (!it1.hasNext()) {
                    break;
                }
                clientMethod = it1.next();
            }

            if (serverMethod == null) {
                if (!it2.hasNext()) {
                    break;
                }
                serverMethod = it2.next();
                serverMethodId++;
            }

            Object returnType;
            String methodName;
            Object[] ptypes;

            int cmp = clientMethod.compareTo(serverMethod);

            if (cmp > 0) {
                // Attempt to implement a method that only exists on the server-side.
                try {
                    returnType = loadClass(serverMethod.returnType());
                    methodName = serverMethod.name();
                    List<String> pnames = serverMethod.parameterTypes();
                    ptypes = new Object[pnames.size()];
                    int i = 0;
                    for (String pname : pnames) {
                        ptypes[i++] = loadClass(pname);
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

            if (clientMethod.isBatchedImmediate() && cmp >= 0) {
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
                mm.new_(NoSuchMethodError.class).throw_();
                clientMethod = null;
                continue;
            }

            Class<?> remoteFailureClass;

            if (cmp > 0) {
                // Use the default failure exception for a method that only exists on the
                // server-side.
                remoteFailureClass = classFor(mm, mClientInfo.remoteFailureException());
            } else {
                String remoteFailureName = clientMethod.remoteFailureException();
                remoteFailureClass = classFor(mm, remoteFailureName);

                for (String ex : clientMethod.exceptionTypes()) {
                    if (!ex.equals(remoteFailureName)) {
                        mm.throws_(ex);
                    }
                }
            }

            mm.throws_(remoteFailureClass);

            if (clientMethod.isPiped() != serverMethod.isPiped()) {
                // Not expected.
                mm.new_(IncompatibleClassChangeError.class).throw_();
                clientMethod = null;
                serverMethod = null;
                continue;
            }

            var supportVar = mm.field("support").getOpaque();

            if (serverMethod.isDisposer()) {
                mm.field("support").setOpaque(supportVar.invoke("dispose", mm.this_()));
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

            var pipeVar = supportVar.invoke("connect", remoteFailureClass);

            Label invokeStart = mm.label().here();
            Label invokeEnd = mm.label();

            pipeVar.invoke("writeLong", mm.field("id"));

            Variable thrownVar = null;
            Label throwException = null;

            Class<?> returnClass = null;
            Variable typeIdVar = null;
            Variable aliasIdVar = null;

            if (serverMethod.isBatched() && !isVoid(returnType)) {
                returnClass = classFor(mm, returnType);
                typeIdVar = supportVar.invoke("remoteTypeId", returnClass);
                aliasIdVar = supportVar.invoke("newAliasId");

                if (batchedImmediateMethodId >= 0) {
                    Label hasType = mm.label();
                    typeIdVar.ifNe(0, hasType);

                    // Must call the immediate variant.

                    writeParams(pipeVar, numMethods,
                                serverMethod, batchedImmediateMethodId, ptypes);
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
                    mm.return_(returnVar);

                    hasType.here();
                }
            }

            writeParams(pipeVar, numMethods, serverMethod, serverMethodId, ptypes);

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
                    mm.return_(stubVar.cast(returnType));
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
                    mm.return_(returnVar);
                }
            }

            var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
            supportVar.invoke("failed", remoteFailureClass, pipeVar, exVar).throw_();

            if (batchedPipeVar != null) {
                mm.finally_(unbatchedStart, () -> supportVar.invoke("rebatch", batchedPipeVar));
            }

            if (thrownVar != null) {
                throwException.here();
                supportVar.invoke("finished", pipeVar);
                thrownVar.throw_();
            }

            clientMethod = null;
            serverMethod = null;
            batchedImmediateMethodId = -1;
        }

        return mStubMaker.finish();
    }

    private static boolean isVoid(Object returnType) {
        return returnType == void.class || returnType.equals("V");
    }

    private static void writeParams(Variable pipeVar, int numMethods,
                                    RemoteMethod method, int methodId, Object[] ptypes)
    {
        if (numMethods < 256) {
            pipeVar.invoke("writeByte", methodId);
        } else if (numMethods < 65536) {
            pipeVar.invoke("writeShort", methodId);
        } else {
            // Impossible case.
            pipeVar.invoke("writeInt", methodId);
        }

        if (ptypes.length <= 0) {
            return;
        }

        // Write all of the non-pipe parameters.

        MethodMaker mm = pipeVar.methodMaker();

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

    private Class<?> classFor(MethodMaker mm, Object obj) throws NoClassDefFoundError {
        return obj instanceof Class ? ((Class<?>) obj) : classFor(mm, (String) obj);
    }

    private Class<?> classFor(MethodMaker mm, String name) throws NoClassDefFoundError {
        Class<?> clazz = mm.var(name).classType();
        if (clazz != null) {
            return clazz;
        }
        try {
            return loadClass(name);
        } catch (ClassNotFoundException e) {
            var error = new NoClassDefFoundError(name);
            error.initCause(e);
            throw error;
        }
    }

    private Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, mStubMaker.classLoader());
    }
}
