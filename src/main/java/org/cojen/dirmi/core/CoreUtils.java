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

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;

import java.net.Socket;
import java.net.StandardSocketOptions;

import java.nio.channels.SocketChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import java.util.function.BiConsumer;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class CoreUtils {
    static final long PROTOCOL_V2 = 4052788960387224692L;

    static final Object MAKER_KEY = new Object();

    static final ThreadLocal<Session> cCurrentSession = new ThreadLocal<>();

    public static void setOptions(Socket s) throws IOException {
        if (s.supportedOptions().contains(StandardSocketOptions.TCP_NODELAY)) {
            s.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
    }

    public static void setOptions(SocketChannel s) throws IOException {
        if (s.supportedOptions().contains(StandardSocketOptions.TCP_NODELAY)) {
            s.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
        s.configureBlocking(true);
    }

    public static Session accessSession(Object obj) {
        if (!(obj instanceof Stub)) {
            throw new IllegalArgumentException();
        }
        return ((StubSupport) Stub.cSupportHandle.getAcquire((Stub) obj)).session();
    }

    public static Session currentSession() {
        Session session = cCurrentSession.get();
        if (session == null) {
            throw new IllegalStateException();
        }
        return session;
    }

    /**
     * Grants the given maker access to the core package.
     */
    static void allowAccess(ClassMaker cm) {
        var thisModule = CoreUtils.class.getModule();
        var thatModule = cm.classLoader().getUnnamedModule();
        thisModule.addExports("org.cojen.dirmi.core", thatModule);
    }

    static boolean isRemote(Class<?> clazz) {
        try {
            return Remote.class.isAssignableFrom(clazz) ||
                // Be lenient and also support java.rmi interfaces.
                java.rmi.Remote.class.isAssignableFrom(clazz);
        } catch (Throwable e) {
            // The java.rmi module might not be found.
            return false;
        }
    }

    static boolean isUnchecked(Class<?> clazz) {
        return RuntimeException.class.isAssignableFrom(clazz)
            || Error.class.isAssignableFrom(clazz);
    }

    /**
     * Given one or more exceptions, returns a reduced set of exception types to check by
     * examining the hierarchy. Unchecked exception types are implicitly considered.
     *
     * @param clazz non-null
     * @param others can be null or empty
     * @return non-empty list
     */
    static List<Class<?>> reduceExceptions(Class<?> clazz, Collection<Class<?>> others) {
        var reduced = new ArrayList<Class<?>>();
        reduced.add(clazz);

        if (others != null) {
            for (Class<?> other : others) {
                reduceExceptions(reduced, other);
            }
        }

        reduceExceptions(reduced, RuntimeException.class);
        reduceExceptions(reduced, Error.class);

        return reduced;
    }

    private static void reduceExceptions(List<Class<?>> reduced, Class<?> clazz) {
        Iterator<Class<?>> it = reduced.iterator();
        while (it.hasNext()) {
            Class<?> ex = it.next();
            if (ex.isAssignableFrom(clazz)) {
                return;
            }
            if (clazz.isAssignableFrom(ex)) {
                it.remove();
            }
        }

        reduced.add(clazz);
    }

    static Class<?> loadClassByNameOrDescriptor(String name, ClassLoader loader)
        throws ClassNotFoundException
    {
        char first = name.charAt(0);

        if (first == '[') {
            // Name is an array descriptor.
            return loadClassByNameOrDescriptor(name.substring(1), loader).arrayType();
        }

        if (first == 'L' && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1).replace('/', '.');
        } else {
            switch (name) {
            case "Z": return boolean.class;
            case "C": return char.class;
            case "F": return float.class;
            case "D": return double.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "I": return int.class;
            case "J": return long.class;
            case "V": return void.class;
            }
        }

        return Class.forName(name, false, loader);
    }

    /**
     * @return false if handler was null or if it threw an exception itself
     */
    static boolean acceptException(BiConsumer<Session<?>, Throwable> h, Session<?> s, Throwable e) {
        if (h != null) {
            try {
                h.accept(s, e);
                return true;
            } catch (Throwable e2) {
                try {
                    e.addSuppressed(e2);
                } catch (Throwable e3) {
                }
            }
        }

        return false;
    }

    public static void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable e) {
            // Ignore.
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> T remoteException(Class<T> remoteFailureEx, Throwable cause) {
        if (cause == null) {
            cause = new RemoteException();
        } else if (cause instanceof EOFException) {
            // EOF is not meaningful in this context, so replace it.
            cause = new ClosedException("Pipe input is closed");
        }

        if (remoteFailureEx.isInstance(cause)) {
            return (T) cause;
        }

        if (remoteFailureEx == RemoteException.class) {
            return (T) new RemoteException(cause);
        }

        return ExceptionWrapper.forClass(remoteFailureEx).wrap(cause);
    }

    /**
     * Rethrows the given exception without the compiler complaining about it being checked or
     * not. Use as follows: {@code throw rethrow(e)}
     */
    public static RuntimeException rethrow(Throwable e) {
        CoreUtils.<RuntimeException>castAndThrow(e);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void castAndThrow(Throwable e) throws T {
        throw (T) e;
    }

    static void writeParam(Variable pipeVar, Variable paramVar) {
        Class<?> type = paramVar.classType();

        if (type == null) {
            pipeVar.invoke("writeObject", paramVar);
        } else if (!type.isPrimitive()) {
            pipeVar.invoke("writeObject", paramVar);
        } else {
            String m;
            if (type == int.class) {
                m = "writeInt";
            } else if (type == long.class) {
                m = "writeLong";
            } else if (type == boolean.class) {
                m = "writeBoolean";
            } else if (type == double.class) {
                m = "writeDouble";
            } else if (type == float.class) {
                m = "writeFloat";
            } else if (type == byte.class) {
                m = "writeByte";
            } else if (type == char.class) {
                m = "writeChar";
            } else if (type == short.class) {
                m = "writeShort";
            } else {
                throw new AssertionError();
            }
            pipeVar.invoke(m, paramVar);
        }
    }

    static void readParam(Variable pipeVar, Variable paramVar) {
        Class<?> type = paramVar.classType();

        if (type == null) {
            paramVar.set(pipeVar.invoke("readObject").cast(paramVar));
        } else if (!type.isPrimitive()) {
            var objectVar = pipeVar.invoke("readObject");
            paramVar.set(type == Object.class ? objectVar : objectVar.cast(type));
        } else {
            String m;
            if (type == int.class) {
                m = "readInt";
            } else if (type == long.class) {
                m = "readLong";
            } else if (type == boolean.class) {
                m = "readBoolean";
            } else if (type == double.class) {
                m = "readDouble";
            } else if (type == float.class) {
                m = "readFloat";
            } else if (type == byte.class) {
                m = "readByte";
            } else if (type == char.class) {
                m = "readChar";
            } else if (type == short.class) {
                m = "readShort";
            } else {
                throw new AssertionError();
            }
            paramVar.set(pipeVar.invoke(m));
        }
    }
}
