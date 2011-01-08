/*
 *  Copyright 2006-2011 Brian S O'Neill
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

package org.cojen.dirmi.trace;

import java.lang.instrument.Instrumentation;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import java.security.SecureRandom;

/**
 * Instrumentation agent for tracing. To install, pass a java argument as follows:
 *
 * <pre>
 * java -javaagent:&lt;path to agent jar file&gt;=&lt;class name of trace handler&gt;[;&lt;argument&gt;]
 * </pre>
 *
 * For example:
 *
 * <pre>
 * java -javaagent:dirmi-1.0.jar=org.cojen.dirmi.trace.SimpleHandler ...
 * </pre>
 *
 * Handlers may accept a single string argument, provided after the handler name,
 * separated by a semi-colon.
 *
 * @author Brian S O'Neill
 * @see org.cojen.dirmi.Trace
 * @see TraceHandler
 */
public class TraceAgent {
    private static final Random cRandom = new SecureRandom();
    private static final Map<Long, TraceAgent> cAgents = new HashMap<Long, TraceAgent>();

    /**
     * Premain method, as required by instrumentation agents.
     *
     * @param agentArgs specify trace handler class name
     * @param inst instrumentation instance passed in by JVM
     * @see TraceHandler
     */
    public static void premain(String agentArgs, Instrumentation inst) throws Throwable {
        TraceAgent agent = new TraceAgent(agentArgs);
        inst.addTransformer(new Transformer(agent));
    }

    /**
     * Method called by instrumented class to get reference to agent.
     */
    public static TraceAgent getTraceAgent(long id) {
        return cAgents.get(id);
    }

    private static synchronized long registerAgent(TraceAgent agent) {
        Long id;
        do {
            id = cRandom.nextLong();
        } while (cAgents.containsKey(id));
        cAgents.put(id, agent);
        return id;
    }

    private final long mAgentId;
    private final TracedMethodRegistry mRegistry = new TracedMethodRegistry();
    private final TraceHandler mHandler;

    private TraceAgent(String agentArgs) throws Throwable {
        if (agentArgs == null) {
            throw new IllegalArgumentException
                ("Must pass handler class name. For example, \"" +
                 "java -javaagent:<path to agent jar file>=<class name of trace handler> ...\"");
        }

        String name, arg;

        int index = agentArgs.indexOf(';');
        if (index < 0) {
            name = agentArgs;
            arg = null;
        } else {
            name = agentArgs.substring(0, index);
            arg = agentArgs.substring(index + 1);
        }

        Class handlerClass = Class.forName(name);

        if (!TraceHandler.class.isAssignableFrom(handlerClass)) {
            throw new IllegalArgumentException
                ("Class doesn't implement TraceHandler: " + handlerClass.getName());
        }

        Constructor ctor;
        boolean passArg;
        
        if (arg == null) {
            try {
                ctor = handlerClass.getConstructor(TraceToolbox.class);
                passArg = false;
            } catch (NoSuchMethodException e) {
                try {
                    ctor = handlerClass.getConstructor(TraceToolbox.class, String.class);
                } catch (NoSuchMethodException e2) {
                    throw e;
                }
                passArg = true;
            }
        } else {
            try {
                ctor = handlerClass.getConstructor(TraceToolbox.class, String.class);
                passArg = true;
            } catch (NoSuchMethodException e) {
                try {
                    ctor = handlerClass.getConstructor(TraceToolbox.class);
                } catch (NoSuchMethodException e2) {
                    throw e;
                }
                passArg = false;
            }
        }

        TraceToolbox toolbox = new TraceToolbox(mRegistry);

        Object[] ctorArgs = new Object[passArg ? 2 : 1];
        ctorArgs[0] = toolbox;
        if (passArg) {
            ctorArgs[1] = arg;
        }

        TraceHandler handler;
        try {
            handler = (TraceHandler) ctor.newInstance(ctorArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause == null ? e : cause;
        }

        mAgentId = registerAgent(this);
        mHandler = handler;
    }

    /**
     * Method called by instrumented class to get the trace handler.
     */
    public TraceHandler getTraceHandler() {
        return mHandler;
    }

    /**
     * Method called by instrumented class to register traced methods.
     */
    public void registerTraceMethod(int mid, String operation, Class clazz,
                                    String methodName, Class returnType, Class... paramTypes)
    {
        mRegistry.registerMethod
            (mid, new TracedMethod(mid, operation, clazz, methodName, returnType, paramTypes));
    }

    public long getAgentId() {
        return mAgentId;
    }

    TraceModes getTraceModes(String className) {
        return mHandler.getTraceModes(className);
    }

    int reserveMethod(boolean root, boolean graft) {
        return mRegistry.reserveMethod(root, graft);
    }
}
