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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Pipe;

/**
 * Makes classes which contain static methods which are responsible for reading parameters,
 * invoking a server-side method, and writing a response. Is used by Skeleton instances.
 *
 * Each method assumes one of these forms:
 *
 *    static <context> <name>(RemoteObject, Pipe, <context>)
 *
 *    static <context> <name>(RemoteObject, Pipe, <context>, SkeletonSupport support)
 *
 * The latter form is used by batched methods which return an object.
 *
 * See {@link Skeleton#invoke} regarding how exceptions should be handled.
 *
 * @author Brian S O'Neill
 */
final class SkeletonInvokerMaker {
    private static final SoftCache<Class<?>, Class<?>> cCache = new SoftCache<>();

    /**
     * @param type non-null remote interface to examine
     * @throws IllegalArgumentException if type is malformed
     * @throws NoClassDefFoundError if the remote failure class isn't found or if the batched
     * remote object class isn't found
     */
    static Class<?> invokerFor(Class<?> type) {
        Class<?> invoker = cCache.get(type);
        if (invoker == null) synchronized (cCache) {
            invoker = cCache.get(type);
            if (invoker == null) {
                invoker = new SkeletonInvokerMaker(type).finish();
                cCache.put(type, invoker);
            }
        }
        return invoker;
    }

    private final Class<?> mType;
    private final RemoteInfo mInfo;

    private SkeletonInvokerMaker(Class<?> type) {
        mType = type;
        mInfo = RemoteInfo.examine(type);
    }

    private Class<?> finish() {
        Set<RemoteMethod> methods = mInfo.remoteMethods();

        if (methods.isEmpty()) {
            return Object.class;
        }

        ClassMaker cm = ClassMaker.begin
            (mType.getName(), mType.getClassLoader(), CoreUtils.MAKER_KEY)
            .final_().sourceFile(SkeletonInvokerMaker.class.getSimpleName());
        CoreUtils.allowAccess(cm);

        var methodNames = new HashMap<String, Integer>();

        for (RemoteMethod rm : methods) {
            String name = generateMethodName(methodNames, rm);

            MethodMaker mm;
            if (!rm.isBatched() || rm.returnType().equals("V")) {
                mm = cm.addMethod(Object.class, name, mType, Pipe.class, Object.class);
            } else {
                mm = cm.addMethod(Object.class, name, mType, Pipe.class, Object.class,
                                  SkeletonSupport.class);
            }

            mm.static_();

            final var remoteVar = mm.param(0);
            final var pipeVar = mm.param(1);
            final var contextVar = mm.param(2);

            List<String> paramTypes = rm.parameterTypes();
            var paramVars = new Variable[paramTypes.size()];
            boolean isPiped = rm.isPiped();
            boolean findPipe = isPiped;

            for (int i=0; i<paramVars.length; i++) {
                var paramVar = mm.var(paramTypes.get(i));
                if (findPipe && paramVar.classType() == Pipe.class) {
                    paramVar = pipeVar;
                    findPipe = false;
                } else {
                    CoreUtils.readParam(pipeVar, paramVar);
                }
                paramVars[i] = paramVar;
            }

            Variable aliasIdVar = null;
            if (rm.isBatched() && !rm.returnType().equals("V")) {
                aliasIdVar = pipeVar.invoke("readLong");
            }

            final var skeletonClassVar = mm.var(Skeleton.class);

            if (rm.isBatched() && !rm.isBatchedImmediate()) {
                // Check if an exception was encountered and stop calling any more batched
                // methods if so.
                var exceptionVar = skeletonClassVar.invoke("batchException", contextVar);

                Label invokeStart = mm.label();
                Label skip = mm.label();

                if (aliasIdVar == null) {
                    skip = mm.label();
                    exceptionVar.ifNe(null, skip);
                } else {
                    exceptionVar.ifEq(null, invokeStart);
                    var supportVar = mm.param(3);
                    supportVar.invoke("writeDisposed", pipeVar, aliasIdVar, exceptionVar);
                    mm.goto_(skip);
                }

                invokeStart.here();
                var serverVar = remoteVar.invoke(rm.name(), (Object[]) paramVars);
                Label invokeEnd = mm.label().here();

                if (serverVar != null) {
                    var supportVar = mm.param(3);
                    supportVar.invoke("createSkeletonAlias", serverVar, aliasIdVar);
                }

                contextVar.set(skeletonClassVar.invoke("batchInvokeSuccess", contextVar));

                skip.here();
                mm.return_(contextVar);

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
                contextVar.set(skeletonClassVar.invoke("batchInvokeFailure",
                                                       pipeVar, contextVar, exVar));

                if (aliasIdVar != null) {
                    var supportVar = mm.param(3);
                    supportVar.invoke("writeDisposed", pipeVar, aliasIdVar, exVar);
                }

                mm.return_(contextVar);
            } else if (isPiped) {
                var resultVar = skeletonClassVar.invoke("batchFinish", pipeVar, contextVar);
                Label invokeStart = mm.label();
                resultVar.ifLt(0, invokeStart);
                pipeVar.invoke("flush");
                // If result is less than or equal to 0, then the batch finished without an
                // exception. Otherwise, this method should be skipped.
                resultVar.ifLe(0, invokeStart);
                mm.return_(null);

                invokeStart.here();
                remoteVar.invoke(rm.name(), (Object[]) paramVars);
                Label invokeEnd = mm.label().here();

                mm.return_(skeletonClassVar.field("STOP_READING"));

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
                mm.new_(UncaughtException.class, exVar).throw_();
            } else {
                Label finished = mm.label();
                // If result is greater than 0, then the batch finished with an exception and
                // so this method should be skipped.
                skeletonClassVar.invoke("batchFinish", pipeVar, contextVar).ifGt(0, finished);

                Label invokeStart = mm.label().here();
                var resultVar = remoteVar.invoke(rm.name(), (Object[]) paramVars);
                Label invokeEnd = mm.label().here();

                // Write a null Throwable to indicate success.
                pipeVar.invoke(null, "writeObject", new Object[] {Throwable.class}, new Object[1]);

                if (rm.isBatchedImmediate()) {
                    var supportVar = mm.param(3);
                    supportVar.invoke("writeSkeletonAlias", pipeVar, resultVar, aliasIdVar);
                } else if (resultVar != null) {
                    CoreUtils.writeParam(pipeVar, resultVar);
                }

                finished.here();
                pipeVar.invoke("flush");

                if (!rm.isBatchedImmediate()) {
                    mm.return_(null);
                } else {
                    // Need to keep the batch going, but with a fresh context.
                    mm.return_(skeletonClassVar.invoke("batchInvokeSuccess", (Object) null));
                }

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);

                if (rm.isBatchedImmediate()) {
                    var supportVar = mm.param(3);
                    supportVar.invoke("writeDisposed", pipeVar, aliasIdVar, exVar);
                }

                pipeVar.invoke("writeObject", exVar);
                pipeVar.invoke("flush");
                mm.return_(null);
            }
        }

        return cm.finish();
    }

    static String generateMethodName(Map<String, Integer> methodNames, RemoteMethod rm) {
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
}
