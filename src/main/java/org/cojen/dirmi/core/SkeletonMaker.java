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

import java.lang.invoke.MethodHandles;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.UnimplementedException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class SkeletonMaker<R> {
    private static final SoftCache<Class<?>, SkeletonFactory<?>> cCache = new SoftCache<>();

    /**
     * Returns a new or cached SkeletonFactory.
     *
     * @param type non-null server-side remote interface to examine
     * @throws IllegalArgumentException if type is malformed
     */
    @SuppressWarnings("unchecked")
    static <R> SkeletonFactory<R> factoryFor(Class<R> type) {
        var factory = (SkeletonFactory<R>) cCache.get(type);
        if (factory == null) synchronized (cCache) {
            factory = (SkeletonFactory<R>) cCache.get(type);
            if (factory == null) {
                factory = new SkeletonMaker<R>(type).finishFactory();
                cCache.put(type, factory);
            }
        }
        return factory;
    }

    private final Class<R> mType;
    private final long mTypeId;
    private final RemoteInfo mServerInfo;
    private final ClassMaker mFactoryMaker;
    private final ClassMaker mSkeletonMaker;

    private SkeletonMaker(Class<R> type) {
        mType = type;
        mServerInfo = RemoteInfo.examine(type);
        mTypeId = IdGenerator.next();

        String sourceFile = SkeletonMaker.class.getSimpleName();

        mFactoryMaker = ClassMaker.begin(type.getName(), type.getClassLoader(), CoreUtils.MAKER_KEY)
            .implement(SkeletonFactory.class).final_().sourceFile(sourceFile);
        CoreUtils.allowAccess(mFactoryMaker);

        mSkeletonMaker = mFactoryMaker.another(type.getName())
            .extend(Skeleton.class).final_().sourceFile(sourceFile);

        // Need to define the skeleton constructor (and its dependencies) early in order for
        // the factory to see it.

        mSkeletonMaker.addField(SkeletonSupport.class, "support").private_().final_();
        mSkeletonMaker.addField(mType, "server").private_().final_();

        // Regular skeleton constructor.
        MethodMaker mm = mSkeletonMaker.addConstructor(long.class, SkeletonSupport.class, mType);
        mm.invokeSuperConstructor(mm.param(0));
        mm.field("support").set(mm.param(1));
        mm.field("server").set(mm.param(2));

        // Broken skeleton constructor.
        mm = mSkeletonMaker.addConstructor(Throwable.class, long.class, SkeletonSupport.class);
        mm.invokeSuperConstructor(mm.param(0), mm.param(1));
        mm.field("support").set(mm.param(2));
        mm.field("server").set(null);
    }

    private SkeletonFactory<R> finishFactory() {
        // Need to finish the factory before the skeleton because it's needed by the skeleton.
        mFactoryMaker.addConstructor();

        mFactoryMaker.addMethod(long.class, "typeId").public_().return_(mTypeId);

        // Regular skeleton factory method.
        {
            MethodMaker mm = mFactoryMaker.addMethod
                (Skeleton.class, "newSkeleton", long.class, SkeletonSupport.class, Object.class);
            var skel = mm.new_(mSkeletonMaker, mm.param(0), mm.param(1), mm.param(2).cast(mType));
            mm.public_().return_(skel);
        }

        // Broken skeleton factory method.
        {
            MethodMaker mm = mFactoryMaker.addMethod
                (Skeleton.class, "newSkeleton", Throwable.class, long.class, SkeletonSupport.class);
            var skel = mm.new_(mSkeletonMaker, mm.param(0), mm.param(1), mm.param(2));
            mm.public_().return_(skel);
        }

        // Need to finish this now because the call to get the skeleton lookup forces it to be
        // initialized. This in turn runs the static initializer (see below) which needs to be
        // able to see the factory class.
        mFactoryMaker.finish();

        // Because the cache maintains a soft reference to the factory, maintain a strong
        // static reference to the singleton factory instance from the skeleton, to prevent the
        // factory instance from being GC'd too soon. If it was GC'd too soon, then new classes
        // might be generated again even when skeleton instances still might exist. Maintaining
        // a strong factory reference from the cache would prevent it from being GC'd at all,
        // because the factory indirectly refers to the cache key.
        mSkeletonMaker.addField(SkeletonFactory.class, "factory").private_().static_().final_();
        MethodMaker mm = mSkeletonMaker.addClinit();
        mm.field("factory").set(mm.new_(mFactoryMaker));

        MethodHandles.Lookup skeletonLookup = finishSkeleton();
        Class<?> skeletonClass = skeletonLookup.lookupClass();

        try {
            var mh = skeletonLookup.findStaticGetter
                (skeletonClass, "factory", SkeletonFactory.class);
            return (SkeletonFactory<R>) mh.invokeExact();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private MethodHandles.Lookup finishSkeleton() {
        mSkeletonMaker.addMethod(Class.class, "type").public_().return_(mType);

        mSkeletonMaker.addMethod(long.class, "typeId").public_().return_(mTypeId);

        {
            MethodMaker mm = mSkeletonMaker.addMethod(mType, "server").public_();
            mm.return_(mm.field("server"));
            mm = mSkeletonMaker.addMethod(Object.class, "server").public_().bridge();
            mm.return_(mm.this_().invoke(mType, "server", null));
        }

        Map<Integer, CaseInfo> caseMap = defineInvokers();

        MethodMaker mm = mSkeletonMaker.addMethod
            (Object.class, "invoke", Pipe.class, Object.class).public_();

        final var pipeVar = mm.param(0);
        final var contextVar = mm.param(1);

        var methodIdVar = CoreUtils.readIntId(pipeVar, caseMap.size());

        var cases = new int[caseMap.size()];
        var labels = new Label[cases.length];

        {
            int i = 0;
            for (Integer methodId : caseMap.keySet()) {
                cases[i] = methodId;
                labels[i] = mm.label();
                i++;
            }
        }

        var supportVar = mm.field("support");

        Label noMethodLabel = mm.label();
        methodIdVar.switch_(noMethodLabel, cases, labels);

        for (int i=0; i<cases.length; i++) {
            labels[i].here();

            CaseInfo ci = caseMap.get(cases[i]);

            if (ci.serverMethod.isUnacknowledged() && mServerInfo.isAutoDispose()) {
                supportVar.invoke("acknowledged", mm.this_());
            }

            if (!ci.serverMethod.isDisposer()) {
                mm.return_(ci.invoke(mm, pipeVar, contextVar, supportVar));
            } else {
                Label invokeStart = mm.label().here();
                mm.return_(ci.invoke(mm, pipeVar, contextVar, supportVar));
                mm.finally_(invokeStart, () -> supportVar.invoke("dispose", mm.this_()));
            }
        }

        // This case shouldn't be possible when the stub class is generated correctly. It won't
        // attempt to call methods which aren't implemented on the server-side.
        noMethodLabel.here();
        var typeNameVar = mm.var(Class.class).set(mType).invoke("getName");
        var messageVar = mm.concat(typeNameVar, '#', methodIdVar);
        mm.new_(UnimplementedException.class, messageVar).throw_();

        return mSkeletonMaker.finishLookup();
    }

    /**
     * Makes private methods which are responsible for reading parameters, invoking a
     * server-side method, and writing a response.
     *
     * Each method assumes one of these forms:
     *
     *    private <context> <name>(Pipe, <context>)
     *
     *    private <context> <name>(Pipe, <context>, SkeletonSupport support)
     *
     * The latter form is used by batched methods which return an object, and also by NoReply
     * methods.
     *
     * See {@link Skeleton#invoke} regarding exception handling
     */
    private Map<Integer, CaseInfo> defineInvokers() {
        SortedSet<RemoteMethod> serverMethods = mServerInfo.remoteMethods();

        if (serverMethods.isEmpty()) {
            return Collections.emptyMap();
        }

        var methodNames = new HashMap<String, Integer>();
        var caseMap = new HashMap<Integer, CaseInfo>();

        int methodId = 0;

        for (RemoteMethod rm : serverMethods) {
            String name = generateMethodName(methodNames, rm);

            caseMap.put(methodId++, new CaseInfo(rm, name));

            MethodMaker mm;
            if (!needsSupport(rm)) {
                mm = mSkeletonMaker.addMethod(Object.class, name, Pipe.class, Object.class);
            } else {
                mm = mSkeletonMaker.addMethod(Object.class, name, Pipe.class, Object.class,
                                              SkeletonSupport.class);
            }

            mm.private_();

            final var pipeVar = mm.param(0);
            final var contextVar = mm.param(1);

            List<String> paramTypes = rm.parameterTypes();
            var paramVars = new Variable[paramTypes.size()];
            boolean isPiped = rm.isPiped();
            boolean findPipe = isPiped;

            if (paramVars.length > 0) {
                var inVar = pipeVar;

                if (rm.isSerialized() && CoreUtils.anyObjectTypes(paramTypes)) {
                    inVar = mm.new_(CoreObjectInputStream.class, pipeVar);
                    ObjectInputFilter filter = rm.objectInputFilter();
                    var filterVar = mm.var(ObjectInputFilter.class).setExact(filter);
                    inVar.invoke("setObjectInputFilter", filterVar);
                }

                for (int i=0; i<paramVars.length; i++) {
                    var paramVar = mm.var(paramTypes.get(i));
                    if (findPipe && paramVar.classType() == Pipe.class) {
                        paramVar = pipeVar;
                        findPipe = false;
                    } else {
                        CoreUtils.readParam(inVar, paramVar);
                    }
                    paramVars[i] = paramVar;
                }
            }

            Variable aliasIdVar = null;
            if (rm.isBatched() && !rm.returnType().equals("V")) {
                aliasIdVar = pipeVar.invoke("readLong");
            }

            if (rm.isBatched() && !rm.isBatchedImmediate()) {
                // Check if an exception was encountered and stop calling any more batched
                // methods if so.
                var exceptionVar = mm.invoke("batchException", contextVar);

                Label invokeStart = mm.label();
                Label skip = mm.label();

                if (aliasIdVar == null) {
                    skip = mm.label();
                    exceptionVar.ifNe(null, skip);
                } else {
                    exceptionVar.ifEq(null, invokeStart);
                    var supportVar = mm.param(2);
                    Class<?> returnType = mm.var(rm.returnType()).classType();
                    supportVar.invoke("createBrokenSkeletonAlias",
                                      returnType, aliasIdVar, exceptionVar);
                    mm.goto_(skip);
                }

                invokeStart.here();
                var serverVar = mm.field("server").get();
                var resultVar = serverVar.invoke(rm.name(), (Object[]) paramVars);
                if (resultVar != null) {
                    mm.invoke("batchedResultCheck", serverVar, rm.name(), resultVar);
                }
                Label invokeEnd = mm.label().here();

                if (resultVar != null) {
                    var supportVar = mm.param(2);
                    supportVar.invoke("createSkeletonAlias", resultVar, aliasIdVar);
                }

                contextVar.set(mm.invoke("batchInvokeSuccess", contextVar));

                skip.here();
                mm.return_(contextVar);

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
                checkException(exVar);

                contextVar.set(mm.invoke("batchInvokeFailure", pipeVar, contextVar, exVar));

                if (aliasIdVar != null) {
                    var supportVar = mm.param(2);
                    supportVar.invoke("createBrokenSkeletonAlias",
                                      resultVar.classType(), aliasIdVar, exVar);
                }

                mm.return_(contextVar);
            } else if (isPiped) {
                var batchResultVar = mm.invoke("batchFinish", pipeVar, contextVar);
                Label invokeStart = mm.label();
                // If the batch result is less than 0, then no batch was in progress.
                batchResultVar.ifLt(0, invokeStart);
                pipeVar.invoke("flush"); // flush the batch response
                // If the batch result is 0, then the batch finished without an exception.
                // Otherwise, this method should be skipped.
                batchResultVar.ifEq(0, invokeStart);
                mm.return_(null);

                invokeStart.here();
                mm.field("server").invoke(rm.name(), (Object[]) paramVars);
                Label invokeEnd = mm.label().here();

                mm.return_(mm.field("STOP_READING"));

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
                checkException(exVar);

                mm.new_(UncaughtException.class, exVar).throw_();
            } else {
                var batchResultVar = mm.invoke("batchFinish", pipeVar, contextVar);

                Label invokeStart = mm.label();
                Label finished = mm.label();

                if (rm.isNoReply()) {
                    // If the batch result is less than 0, then no batch was in progress.
                    batchResultVar.ifLt(0, invokeStart);
                    pipeVar.invoke("flush"); // flush the batch response
                    // If the batch result isn't 0, then the batch finished with an exception,
                    // and so this method should be skipped.
                    batchResultVar.ifNe(0, finished);
                } else {
                    // If the method will write a reply, there's no immediate flush step, and
                    // so the check against batchResultVar can be simplified. Only jump ahead
                    // if there was an exception.
                    batchResultVar.ifGt(0, finished);
                }

                invokeStart.here();
                var serverVar = mm.field("server").get();
                var resultVar = serverVar.invoke(rm.name(), (Object[]) paramVars);
                if (resultVar != null && rm.isBatchedImmediate()) {
                    mm.invoke("batchedResultCheck", serverVar, rm.name(), resultVar);
                }
                Label invokeEnd = mm.label().here();

                if (rm.isNoReply()) {
                    finished.here();
                } else {
                    // Write a null Throwable to indicate success.
                    pipeVar.invoke("writeNull");

                    if (rm.isBatchedImmediate()) {
                        var supportVar = mm.param(2);
                        supportVar.invoke("writeSkeletonAlias", pipeVar, resultVar, aliasIdVar);
                    } else if (resultVar != null) {
                        Variable outVar = pipeVar;
                        if (rm.isSerialized() && CoreUtils.isObjectType(resultVar)) {
                            outVar = mm.new_(CoreObjectOutputStream.class, pipeVar);
                        }
                        CoreUtils.writeParam(outVar, resultVar);
                        if (outVar != pipeVar) {
                            outVar.invoke("drain");
                        }
                    }

                    finished.here();
                    pipeVar.invoke("flush");
                }

                if (!rm.isBatchedImmediate()) {
                    mm.return_(null);
                } else {
                    // Need to keep the batch going, but with a fresh context.
                    mm.return_(mm.invoke("batchInvokeSuccess", (Object) null));
                }

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
                checkException(exVar);

                if (rm.isNoReply()) {
                    var supportVar = mm.param(2);
                    supportVar.invoke("uncaught", exVar);
                } else {
                    if (rm.isBatchedImmediate()) {
                        // Even though invocation failed with an exception, match the behavior
                        // of a regular batched call. Create a broken skeleton and don't throw
                        // an exception to the caller.
                        pipeVar.invoke("writeNull"); // null indicates success
                        var supportVar = mm.param(2);
                        supportVar.invoke("writeBrokenSkeletonAlias", pipeVar,
                                          resultVar.classType(), aliasIdVar, exVar);
                    } else {
                        pipeVar.invoke("writeObject", exVar);
                    }
                    pipeVar.invoke("flush");
                }

                mm.return_(null);
            }
        }

        return caseMap;
    }

    private void checkException(Variable exVar) {
        exVar.set(exVar.methodMaker().invoke("checkException", exVar));
    }

    private static boolean needsSupport(RemoteMethod rm) {
        return rm.isNoReply() || (rm.isBatched() && !rm.returnType().equals("V"));
    }

    private static String generateMethodName(Map<String, Integer> methodNames, RemoteMethod rm) {
        String name = rm.name();
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

    private record CaseInfo(RemoteMethod serverMethod, String serverMethodName) {
        Variable invoke(MethodMaker mm, Variable pipeVar, Variable contextVar, Variable supportVar)
        {
            if (!needsSupport(serverMethod)) {
                return mm.invoke(serverMethodName, pipeVar, contextVar);
            }
            return mm.invoke(serverMethodName, pipeVar, contextVar, supportVar);
        }
    }
}
