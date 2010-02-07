/*
 *  Copyright 2009-2010 Brian S O'Neill
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

package org.cojen.dirmi.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;

import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.RuntimeClassFile;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.KeyFactory;
import org.cojen.util.SoftValuedHashMap;
import org.cojen.util.ThrowUnchecked;

/**
 * Automatically generates objects which delegate method invocations to another
 * object. This simplifies the process of implementing remote objects which are
 * primarily adapters.
 *
 * @author Brian S O'Neill
 */
public class Wrapper<B, D> {
    private static final Map<Object, Wrapper<?, ?>> cCache;

    static {
        cCache = new SoftValuedHashMap<Object, Wrapper<?, ?>>();
    }

    /**
     * Generates a wrapper from a base type and delegate type. The base type
     * can be an interface or abstract class, but the delegate can be any
     * type. If the base type is an abstract class, it must have at least one
     * public or protected constructor of the form:
     *
     * <ul>
     * <li>no arguments
     * <li>one argument, capable of accepting the delegate type
     * <li>multiple arguments, but the first must be capable of accepting the
     * delegate type
     * </ul>
     *
     * For each public or protected abstract method in the base type, an
     * attempt is made to match the invocation to a method in the delegate
     * type. The method signature must exactly match, and any declared checked
     * exceptions in the delegate type must also be declared by the base type.
     * If any abstract methods cannot be implemented, an
     * IllegalArgumentException is thrown.
     *
     * @param baseType must be a public interface or abstract class
     * @param delegateType any type
     * @throws IllegalArgumentException if type parameters are unsupported
     */
    public static <B, D> Wrapper<B, D> from(Class<B> baseType, Class<D> delegateType) {
        Object key = KeyFactory.createKey(new Object[] {baseType, delegateType});

        synchronized (cCache) {
            Wrapper<B, D> wrapper = (Wrapper<B, D>) cCache.get(key);
            if (wrapper == null) {
                Class<? extends B> adapterClass = generateAdapterClass(baseType, delegateType);

                Constructor<? extends B> ctor = null;
                for (Constructor c : adapterClass.getConstructors()) {
                    if (c.getParameterTypes().length == 1) {
                        ctor = c;
                        break;
                    }
                }

                wrapper = new Wrapper(adapterClass, ctor);

                cCache.put(key, wrapper);
            }
            return wrapper;
        }
    }

    private final Class<? extends B> mAdapterClass;
    private final Constructor<B> mConstructor;

    protected Wrapper(Class<? extends B> adapterClass, Constructor<B> ctor) {
        mAdapterClass = adapterClass;
        mConstructor = ctor;
    }

    /**
     * Returns a wrapper instance around the given delegate.
     *
     * @param delegate delegate to wrap
     * @throws IllegalArgumentException if delegate is null or arguments are
     * required
     * @throws UndeclaredThrowableException if constructor throws a checked exception
     */
    public B wrap(D delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate is null");
        }
        Constructor<B> ctor = mConstructor;
        if (ctor == null) {
            throw new IllegalArgumentException("Arguments are required");
        }
        try {
            return ctor.newInstance(delegate);
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            ThrowUnchecked.fireDeclaredCause(e);
            throw null;
        }
    }

    /**
     * Returns a constructor for the generated wrapper. The first argument must
     * match the delegate type.
     */
    public Constructor<? extends B> getConstructor(Class<?>... arguments)
        throws NoSuchMethodException
    {
        return mAdapterClass.getConstructor(arguments);
    }

    private static <B, D> Class<? extends B>
        generateAdapterClass(Class<B> baseType, Class<?> delegateType)
    {
        if (baseType == null || delegateType == null) {
            throw new IllegalArgumentException();
        }
        if (!baseType.isInterface() && !Modifier.isAbstract(baseType.getModifiers())) {
            throw new IllegalArgumentException
                ("Must be an interface or abstract class: " + baseType);
        }
        checkClassAccess(baseType);
        checkClassAccess(delegateType);

        List<Constructor> superConstructors;
        if (baseType.isInterface()) {
            superConstructors = Collections.emptyList();
        } else {
            superConstructors = gatherConstructors(baseType, delegateType);
            if (superConstructors.isEmpty()) {
                throw new IllegalArgumentException
                    ("No applicable constructor found in base type: " + baseType);
            }
        }

        Iterable<Method> abstractMethods;
        {
            Map<Object, Method> abstractMethodMap = new HashMap<Object, Method>();
            gatherAbstractMethods(baseType, new HashSet<Class>(),
                                  new HashMap<Object, Method>(), abstractMethodMap);
            abstractMethods = abstractMethodMap.values();
        }

        String baseName = baseType.getName();
        if (baseName.startsWith("java.")) {
            baseName = baseName.replace('.', '$');
        }

        String superName = baseType.isInterface() ? null : baseType.getName();

        RuntimeClassFile cf = new RuntimeClassFile(baseName, superName, baseType.getClassLoader());
        cf.setSourceFile(Wrapper.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");
        if (baseType.isInterface()) {
            cf.addInterface(baseType);
        }

        final TypeDesc delegateDesc = TypeDesc.forClass(delegateType);

        cf.addField(Modifiers.PRIVATE.toFinal(true), "delegate", delegateDesc);

        if (superConstructors.isEmpty()) {
            addPlainConstructor(cf, delegateDesc);
        } else {
            boolean plainAdded = false;

            for (Constructor ctor : superConstructors) {
                Class[] paramTypes = ctor.getParameterTypes();

                plain: if (paramTypes.length == 0) {
                    if (!plainAdded) {
                        plainAdded = true;
                        // Prefer any one argument constructor.
                        for (Constructor ctor2 : superConstructors) {
                            if (ctor2.getParameterTypes().length == 1) {
                                break plain;
                            }
                        }
                        addPlainConstructor(cf, delegateDesc);
                    }
                    continue;
                }

                TypeDesc[] paramDescs = new TypeDesc[paramTypes.length];
                for (int i=1; i<paramTypes.length; i++) {
                    paramDescs[i] = TypeDesc.forClass(paramTypes[i]);
                }

                paramDescs[0] = delegateDesc;
                CodeBuilder b = new CodeBuilder(cf.addConstructor(Modifiers.PUBLIC, paramDescs));

                b.loadThis();
                for (int i=0; i<paramTypes.length; i++) {
                    b.loadLocal(b.getParameter(i));
                }

                paramDescs[0] = TypeDesc.forClass(paramTypes[0]);
                b.invokeSuperConstructor(paramDescs);

                b.loadThis();
                b.loadLocal(b.getParameter(0));
                b.storeField("delegate", delegateDesc);
                b.returnVoid();
            }
        }

        for (Method abstractMethod : abstractMethods) {
            Method delegateMethod;
            try {
                delegateMethod = delegateType.getMethod
                    (abstractMethod.getName(), abstractMethod.getParameterTypes());
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException
                    ("Delegate does not contain matching method: " + abstractMethod);
            }

            if (!abstractMethod.getReturnType().isAssignableFrom(delegateMethod.getReturnType())) {
                throw new IllegalArgumentException
                    ("Delegate method return type is not applicable: " + delegateMethod +
                     ", expected: " + abstractMethod);
            }

            checkCheckedExceptions(abstractMethod, delegateMethod);

            CodeBuilder b = new CodeBuilder(cf.addMethod(abstractMethod));
            b.loadThis();
            b.loadField("delegate", delegateDesc);
            int count = b.getParameterCount();
            for (int i=0; i<count; i++) {
                b.loadLocal(b.getParameter(i));
            }
            b.invoke(delegateMethod);
            b.returnValue(TypeDesc.forClass(abstractMethod.getReturnType()));
        }

        return cf.defineClass();
    }

    private static void addPlainConstructor(RuntimeClassFile cf, TypeDesc delegateDesc) {
        CodeBuilder b = new CodeBuilder
            (cf.addConstructor(Modifiers.PUBLIC, new TypeDesc[] {delegateDesc}));
        b.loadThis();
        b.invokeSuperConstructor(null);
        b.loadThis();
        b.loadLocal(b.getParameter(0));
        b.storeField("delegate", delegateDesc);
        b.returnVoid();
    }

    private static List<Constructor> gatherConstructors
        (Class<?> baseType, Class<?> delegateType)
    {
        List<Constructor> ctors = new ArrayList<Constructor>();

        outer: for (Constructor ctor : baseType.getDeclaredConstructors()) {
            int modifiers = ctor.getModifiers();
            if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
                continue;
            }

            Class[] paramTypes = ctor.getParameterTypes();
            if (paramTypes != null && paramTypes.length > 0) {
                if (!paramTypes[0].isAssignableFrom(delegateType)) {
                    continue;
                }
                for (Class type : paramTypes) {
                    if (!Modifier.isPublic(type.getModifiers())) {
                        continue outer;
                    }
                }
            }

            ctors.add(ctor);
        }

        return ctors;
    }

    private static void gatherAbstractMethods(Class<?> clazz,
                                              Set<Class> seen,
                                              Map<Object, Method> allMethods,
                                              Map<Object, Method> abstractMethods)
    {
        if (clazz == null || !seen.add(clazz)) {
            return;
        }

        for (Method method : clazz.getDeclaredMethods()) {
            Object key = KeyFactory.createKey(new Object[] {
                method.getName(), method.getReturnType(), method.getParameterTypes()
            });

            if (!allMethods.containsKey(key)) {
                allMethods.put(key, method);

                if (Modifier.isAbstract(method.getModifiers())) {
                    checkMemberAccess(method);
                    checkParameterAccess(method, method.getParameterTypes());
                    if (!abstractMethods.containsKey(key)) {
                        abstractMethods.put(key, method);
                    }
                }
            }
        }

        gatherAbstractMethods(clazz.getSuperclass(), seen, allMethods, abstractMethods);

        for (Class iface : clazz.getInterfaces()) {
            gatherAbstractMethods(iface, seen, allMethods, abstractMethods);
        }
    }

    private static void checkClassAccess(Class<?> clazz) {
        if (!Modifier.isPublic(clazz.getModifiers())) {
            throw new IllegalArgumentException("Must be public: " + clazz);
        }
    }

    private static void checkMemberAccess(Member member) {
        int modifiers = member.getModifiers();
        if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
            throw new IllegalArgumentException("Must be public or protected: " + member);
        }
    }

    private static void checkParameterAccess(Member member, Class[] paramTypes) {
        if (paramTypes != null) {
            for (Class type : paramTypes) {
                if (!Modifier.isPublic(type.getModifiers())) {
                    throw new IllegalArgumentException
                        ("Not all parameter types are public: " + member + ", " + type.getName());
                }
            }
        }
    }

    private static void checkCheckedExceptions(Method abstractMethod, Method delegateMethod) {
        Class[] delegateExceptions = delegateMethod.getExceptionTypes();
        if (delegateExceptions.length == 0) {
            return;
        }

        Class[] abstractExceptions = abstractMethod.getExceptionTypes();

        outer: for (Class declared : delegateExceptions) {
            if (RuntimeException.class.isAssignableFrom(declared) ||
                Error.class.isAssignableFrom(declared))
            {
                continue;
            }

            for (Class allowed : abstractExceptions) {
                if (allowed.isAssignableFrom(declared)) {
                    continue outer;
                }
            }

            throw new IllegalArgumentException
                ("Delegate method declares throwing a checked exception not declared " +
                 "by base type method: " + abstractMethod + " does not support all exceptions " +
                 "declared by " + delegateMethod);
        }
    }
}
