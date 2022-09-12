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

import java.lang.invoke.MethodHandles;
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
    private static final SoftCache<TypeInfoKey, StubFactory> cCache = new SoftCache<>();

    /**
     * Returns a new or cached StubFactory.
     *
     * @param type non-null client-side remote interface to examine
     * @param info server-side type information
     * @throws IllegalArgumentException if type is malformed
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    static StubFactory factoryFor(Class<?> type, RemoteInfo info) {
        var key = new TypeInfoKey(type, info);
        var factory = cCache.get(key);
        if (factory == null) synchronized (cCache) {
            factory = cCache.get(key);
            if (factory == null) {
                factory = new StubMaker(type, info).finishFactory();
                cCache.put(key, factory);
            }
        }
        return factory;
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

        mFactoryMaker = ClassMaker.begin(type.getName(), type.getClassLoader(), CoreUtils.MAKER_KEY)
            .implement(StubFactory.class).final_().sourceFile(sourceFile);
        CoreUtils.allowAccess(mFactoryMaker);

        mStubMaker = mFactoryMaker.another(type.getName())
            .extend(Stub.class).implement(type).final_().sourceFile(sourceFile);

        // Need to define the stub constructor (and its dependencies) early in order for the
        // factory to see it.

        mStubMaker.addField(StubSupport.class, "support").private_();

        MethodMaker mm = mStubMaker.addConstructor(long.class, StubSupport.class);
        mm.invokeSuperConstructor(mm.param(0));
        mm.field("support").set(mm.param(1));
        mm.var(VarHandle.class).invoke("storeStoreFence");
    }

    /**
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    private StubFactory finishFactory() {
        // Need to finish the factory before the stub because it's needed by the stub.
        mFactoryMaker.addConstructor();

        MethodMaker mm = mFactoryMaker.addMethod
            (Stub.class, "newStub", long.class, StubSupport.class);
        mm.public_().return_(mm.new_(mStubMaker, mm.param(0), mm.param(1)));

        // Need to finish this now because the call to get the stub lookup forces it to be
        // initialized. This in turn runs the static initializer (see below) which needs to be
        // able to see the factory class.
        Class<?> factoryClass = mFactoryMaker.finish();

        // Because the cache maintains a soft reference to the factory, maintain a strong
        // static reference to the singleton factory instance from the stub, to prevent the
        // factory instance from being GC'd too soon. If it was GC'd too soon, then new classes
        // might be generated again even when stub instances still might exist. Maintaining a
        // strong factory reference from the cache would prevent it from being GC'd at all,
        // because the factory indirectly refers to the cache key.
        mStubMaker.addField(StubFactory.class, "factory").private_().static_().final_();
        mm = mStubMaker.addClinit();
        mm.field("factory").set(mm.new_(mFactoryMaker));

        MethodHandles.Lookup stubLookup = finishStub();
        Class<?> stubClass = stubLookup.lookupClass();

        try {
            var mh = stubLookup.findStaticGetter(stubClass, "factory", StubFactory.class);
            return (StubFactory) mh.invokeExact();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Returns a class which has a constructor that accepts a StubSupport instance.
     *
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    private MethodHandles.Lookup finishStub() {
        SortedSet<RemoteMethod> clientMethods = mClientInfo.remoteMethods();
        SortedSet<RemoteMethod> serverMethods = mServerInfo.remoteMethods();

        int numMethods = serverMethods.size();

        Iterator<RemoteMethod> it1 = clientMethods.iterator();
        Iterator<RemoteMethod> it2 = serverMethods.iterator();

        RemoteMethod clientMethod = null;

        RemoteMethod serverMethod = null;
        int serverMethodId = -1;

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
                remoteFailureClass = classFor(mClientInfo.remoteFailureException());
            } else {
                String remoteFailureName = clientMethod.remoteFailureException();
                remoteFailureClass = classFor(remoteFailureName);

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

            if (numMethods < 256) {
                pipeVar.invoke("writeByte", serverMethodId);
            } else if (numMethods < 65536) {
                pipeVar.invoke("writeShort", serverMethodId);
            } else {
                // Impossible case.
                pipeVar.invoke("writeInt", serverMethodId);
            }

            if (ptypes.length > 0) {
                // Write all of the non-pipe parameters.
                if (!serverMethod.isPiped()) {
                    for (int i=0; i<ptypes.length; i++) {
                        CoreUtils.writeParam(pipeVar, mm.param(i));
                    }
                } else {
                    String pipeName = Pipe.class.getName();
                    for (int i=0; i<ptypes.length; i++) {
                        Object ptype = ptypes[i];
                        if (ptype != Pipe.class && !ptype.equals(pipeName)) {
                            CoreUtils.writeParam(pipeVar, mm.param(i));
                        }
                    }
                }
            }

            Variable thrownVar = null;
            Label throwException = null;

            if (serverMethod.isPiped()) {
                invokeEnd.here();
                supportVar.invoke("release", pipeVar);
                mm.return_(pipeVar);
            } else if (serverMethod.isBatched()) {
                invokeEnd.here();
                supportVar.invoke("batched", pipeVar);
                if (returnType == void.class || returnType.equals("V")) {
                    mm.return_();
                } else {
                    Class<?> returnClass = classFor(returnType);
                    mm.return_(supportVar.invoke
                               ("createBatchedRemote", remoteFailureClass, pipeVar, returnClass));
                }
            } else {
                pipeVar.invoke("flush");
                thrownVar = supportVar.invoke("readResponse", pipeVar);
                throwException = mm.label();
                thrownVar.ifNe(null, throwException);

                if (returnType == void.class || returnType.equals("V")) {
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
                // FIXME: Should handle case where thrown exception is checked but not declared
                // by the client-side interface. (?)
                thrownVar.throw_();
            }

            clientMethod = null;
            serverMethod = null;
        }

        return mStubMaker.finishLookup();
    }

    private Class<?> classFor(Object obj) throws NoClassDefFoundError {
        return obj instanceof Class ? ((Class<?>) obj) : classFor((String) obj);
    }

    private Class<?> classFor(String name) throws NoClassDefFoundError {
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