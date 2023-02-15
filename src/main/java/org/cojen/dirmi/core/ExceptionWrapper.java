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
import java.lang.invoke.MethodType;

import java.lang.reflect.Modifier;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

/**
 * Wraps an exception using a hidden class, in order to provide a clean stack trace. If using
 * reflection, a few extra traces would be added which expose the reflection classes.
 *
 * <p>If the wrapped exception type isn't public or doesn't have an appropriate constructor, then
 * no wrapping occurs and instead original exception is returned as-is.
 *
 * @author Brian S O'Neill
 */
abstract class ExceptionWrapper {
    private static final SoftCache<Class<?>, ExceptionWrapper> cCache = new SoftCache<>();

    static ExceptionWrapper forClass(Class<?> exceptionType) {
        ExceptionWrapper wrapper = cCache.get(exceptionType);

        if (wrapper == null) {
            synchronized (cCache) {
                wrapper = cCache.get(exceptionType);
                if (wrapper == null) {
                    wrapper = makeWrapper(exceptionType);
                    cCache.put(exceptionType, wrapper);
                }
            }
        }

        return wrapper;
    }

    /**
     * @param extraMessage if non-null, is appended to the exception message (in parens) if
     * possible
     */
    abstract <T extends Throwable> T wrap(Throwable cause, Object extraMessage);

    private static ExceptionWrapper makeWrapper(Class<?> exceptionType) {
        int style;

        findCtor: {
            if (Modifier.isPublic(exceptionType.getModifiers())) {
                try {
                    exceptionType.getConstructor(String.class, Throwable.class);
                    style = 1;
                    break findCtor;
                } catch (NoSuchMethodException e) {
                }

                try {
                    exceptionType.getConstructor(String.class);
                    style = 2;
                    break findCtor;
                } catch (NoSuchMethodException e) {
                }

                try {
                    exceptionType.getConstructor(Throwable.class);
                    style = 3;
                    break findCtor;
                } catch (NoSuchMethodException e) {
                }

                try {
                    exceptionType.getConstructor();
                    style = 4;
                    break findCtor;
                } catch (NoSuchMethodException e) {
                }
            }

            // Give up and always return the cause anyhow, possibly unchecked.
            return Rethrow.THE;
        }

        ClassMaker cm = ClassMaker.begin(null, MethodHandles.lookup());
        cm.extend(ExceptionWrapper.class).addConstructor();

        MethodMaker mm = cm.addMethod(Throwable.class, "wrap", Throwable.class, Object.class);

        switch (style) {
        case 1: {
            var messageVar = mm.var(String.class).invoke("valueOf", mm.param(0));
            appendToMessage(messageVar, mm.param(1));
            mm.return_(mm.new_(exceptionType, messageVar, mm.param(0)));
            break;
        }
        case 2: {
            var messageVar = mm.var(String.class).invoke("valueOf", mm.param(0));
            appendToMessage(messageVar, mm.param(1));
            initCauseAndReturn(mm.new_(exceptionType, messageVar));
            break;
        }
        case 3: {
            mm.return_(mm.new_(exceptionType, mm.param(0)));
            break;
        }
        case 4: {
            initCauseAndReturn(mm.new_(exceptionType));
            break;
        }
        default:
            throw new AssertionError();
        }

        MethodHandles.Lookup lookup = cm.finishHidden();
        Class<?> clazz = lookup.lookupClass();

        try {
            return (ExceptionWrapper) lookup.findConstructor
                (clazz, MethodType.methodType(void.class)).invoke();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private static void appendToMessage(Variable messageVar, Variable extraMessageVar) {
        MethodMaker mm = extraMessageVar.methodMaker();
        Label noMessage = mm.label();
        extraMessageVar.ifEq(null, noMessage);
        messageVar.set(mm.concat(messageVar, " (", extraMessageVar, ')'));
        noMessage.here();
    }

    private static void initCauseAndReturn(Variable exVar) {
        MethodMaker mm = exVar.methodMaker();
        Label tryStart = mm.label().here();
        exVar.invoke("initCause", mm.param(0));
        mm.return_(exVar);
        Label tryEnd = mm.label().here();
        mm.catch_(tryStart, tryEnd, Throwable.class);
        // Return the wrapped exception without a cause if initCause throws an exception.
        mm.return_(exVar);
    }

    /**
     * Default case if an appropriate constructor wasn't found.
     */
    private static final class Rethrow extends ExceptionWrapper {
        static final Rethrow THE = new Rethrow();

        private Rethrow() {
        }

        @Override
        @SuppressWarnings("unchecked")
        <T extends Throwable> T wrap(Throwable cause, Object extraMessage) {
            return (T) cause;
        }
    }
}
