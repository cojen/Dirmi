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
 * Each method looks like so:
 *
 *    static <context> <name>(RemoteObject, Pipe, <context>)
 *
 * Other than UncaughtException, any exception thrown by the method indicates a communication
 * failure. UncaughtException is only expected to be thrown from a piped method, and the caller
 * should close the pipe after reporting it.
 *
 * @author Brian S O'Neill
 * @see BatchedContext
 */
final class InvokerMaker {
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
                invoker = new InvokerMaker(type).finish();
                cCache.put(type, invoker);
            }
        }
        return invoker;
    }

    private final Class<?> mType;
    private final RemoteInfo mInfo;

    private InvokerMaker(Class<?> type) {
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
            .final_().sourceFile(InvokerMaker.class.getSimpleName());
        CoreUtils.allowAccess(cm);

        var methodNames = new HashMap<String, Integer>();

        for (RemoteMethod rm : methods) {
            String name = generateMethodName(methodNames, rm);
            MethodMaker mm = cm.addMethod(Object.class, name, mType, Pipe.class, Object.class);
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

            final var contextClassVar = mm.var(BatchedContext.class);

            if (rm.isBatched()) {
                // Check if an exception was encountered and stop calling any more batched
                // exceptions if so.
                Label skip = mm.label();
                contextClassVar.invoke("hasException", contextVar).ifTrue(skip);

                Label invokeStart = mm.label().here();
                var resultVar = remoteVar.invoke(rm.name(), (Object[]) paramVars);
                Label invokeEnd = mm.label().here();

                if (resultVar != null) {
                    contextVar.set(contextClassVar.invoke("addResponse", contextVar, resultVar));
                }

                skip.here();
                mm.return_(contextVar);

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
                mm.return_(contextClassVar.invoke("addException", contextVar, exVar));
            } else if (isPiped) {
                Label hasNoContext = mm.label();
                contextVar.ifEq(null, hasNoContext);
                contextClassVar.invoke("writeResponses", contextVar, pipeVar);
                pipeVar.invoke("flush");
                hasNoContext.here();

                Label invokeStart = mm.label().here();
                remoteVar.invoke(rm.name(), (Object[]) paramVars);
                Label invokeEnd = mm.label().here();

                mm.return_(contextClassVar.field("STOP_READING"));

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
                mm.new_(UncaughtException.class, exVar).throw_();
            } else {
                Label hasNoContext = mm.label();
                contextVar.ifEq(null, hasNoContext);
                contextClassVar.invoke("writeResponses", contextVar, pipeVar);
                hasNoContext.here();

                Label invokeStart = mm.label().here();
                var resultVar = remoteVar.invoke(rm.name(), (Object[]) paramVars);
                Label invokeEnd = mm.label().here();

                // Write a null Throwable to indicate success.
                pipeVar.invoke(null, "writeObject", new Object[] {Throwable.class}, new Object[1]);

                if (resultVar != null) {
                    CoreUtils.writeParam(pipeVar, resultVar);
                }

                pipeVar.invoke("flush");
                mm.return_(null);

                var exVar = mm.catch_(invokeStart, invokeEnd, Throwable.class);
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
