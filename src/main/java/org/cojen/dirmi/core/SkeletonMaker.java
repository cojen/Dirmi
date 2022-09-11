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

import java.util.HashMap;
import java.util.SortedSet;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;

import org.cojen.dirmi.Pipe;

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
    private final RemoteInfo mServerInfo;
    private final Class<?> mInvokerClass;
    private final ClassMaker mFactoryMaker;
    private final ClassMaker mSkeletonMaker;

    private SkeletonMaker(Class<R> type) {
        mType = type;
        mServerInfo = RemoteInfo.examine(type);

        mInvokerClass = InvokerMaker.invokerFor(type);

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

        MethodMaker mm = mSkeletonMaker.addConstructor(long.class, SkeletonSupport.class, mType);
        mm.invokeSuperConstructor(mm.param(0));
        mm.field("support").set(mm.param(1));
        mm.field("server").set(mm.param(2));
    }

    private SkeletonFactory<R> finishFactory() {
        // Need to finish the factory before the skeleton because it's needed by the skeleton.
        mFactoryMaker.addConstructor();

        MethodMaker mm = mFactoryMaker.addMethod
            (Skeleton.class, "newSkeleton", long.class, SkeletonSupport.class, Object.class);
        var skel = mm.new_(mSkeletonMaker, mm.param(0), mm.param(1), mm.param(2).cast(mType));
        mm.public_().return_(skel);

        // Need to finish this now because the call to get the skeleton lookup forces it to be
        // initialized. This in turn runs the static initializer (see below) which needs to be
        // able to see the factory class.
        Class<?> factoryClass = mFactoryMaker.finish();

        // Because the cache maintains a soft reference to the factory, maintain a strong
        // static reference to the singleton factory instance from the skeleton, to prevent the
        // factory instance from being GC'd too soon. If it was GC'd too soon, then new classes
        // might be generated again even when skeleton instances still might exist. Maintaining
        // a strong factory reference from the cache would prevent it from being GC'd at all,
        // because the factory indirectly refers to the cache key.
        mSkeletonMaker.addField(SkeletonFactory.class, "factory").private_().static_().final_();
        mm = mSkeletonMaker.addClinit();
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

        mSkeletonMaker.addMethod(long.class, "typeId").public_().return_(IdGenerator.next());

        {
            MethodMaker mm = mSkeletonMaker.addMethod(mType, "server").public_();
            mm.return_(mm.field("server"));
            mm = mSkeletonMaker.addMethod(Object.class, "server").public_().bridge();
            mm.return_(mm.this_().invoke(mType, "server", null));
        }

        SortedSet<RemoteMethod> serverMethods = mServerInfo.remoteMethods();

        MethodMaker mm = mSkeletonMaker.addMethod
            (Object.class, "invoke", Pipe.class, Object.class).public_();

        final var pipeVar = mm.param(0);
        final var contextVar = mm.param(1);

        Label tryStart = mm.label().here();

        var methodIdVar = mm.var(int.class);

        if (serverMethods.size() < 256) {
            methodIdVar.set(pipeVar.invoke("readUnsignedByte"));
        } else if (serverMethods.size() < 65536) {
            methodIdVar.set(pipeVar.invoke("readUnsignedShort"));
        } else {
            // Impossible case.
            methodIdVar.set(pipeVar.invoke("readInt"));
        }

        var caseMap = new HashMap<Integer, CaseInfo>();
        {
            int methodId = 0;
            var methodNames = new HashMap<String, Integer>();

            for (RemoteMethod serverMethod : serverMethods) {
                String name = InvokerMaker.generateMethodName(methodNames, serverMethod);
                caseMap.put(methodId++, new CaseInfo(serverMethod, name));
            }
        }

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

        var invokerVar = mm.var(mInvokerClass);
        var supportVar = mm.field("support");
        var serverVar = mm.field("server").get();

        Label noMethodLabel = mm.label();
        methodIdVar.switch_(noMethodLabel, cases, labels);

        for (int i=0; i<cases.length; i++) {
            labels[i].here();

            CaseInfo ci = caseMap.get(cases[i]);

            if (!ci.serverMethod.isDisposer()) {
                mm.return_(invokerVar.invoke(ci.serverMethodName, serverVar, pipeVar, contextVar));
            } else {
                Label invokeStart = mm.label().here();
                mm.return_(invokerVar.invoke(ci.serverMethodName, serverVar, pipeVar, contextVar));
                mm.finally_(invokeStart, () -> supportVar.invoke("dispose", mm.this_()));
            }
        }

        // This case shouldn't be possible when the stub class is generated correctly. It won't
        // attempt to call methods which aren't implemented on the server-side.
        noMethodLabel.here();
        var typeNameVar = mm.var(Class.class).set(mType).invoke("getName");
        var messageVar = mm.concat(typeNameVar, '#', methodIdVar);
        mm.new_(NoSuchMethodError.class, messageVar).throw_();

        Label tryEnd = mm.label().here();
        var exVar = mm.catch_(tryStart, tryEnd, Throwable.class);
        mm.return_(supportVar.invoke("handleException", pipeVar, exVar));

        return mSkeletonMaker.finishLookup();
    }

    private static class CaseInfo {
        final RemoteMethod serverMethod;
        final String serverMethodName;

        CaseInfo(RemoteMethod serverMethod, String serverMethodName) {
            this.serverMethod = serverMethod;
            this.serverMethodName = serverMethodName;
        }
    }
}
