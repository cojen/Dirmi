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

import java.lang.reflect.Constructor;

import java.net.Socket;
import java.net.StandardSocketOptions;

import java.nio.channels.SocketChannel;

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

    static final ThreadLocal<Session> CURRENT_SESSION = new ThreadLocal<>();

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
        return ((StubSupport) Stub.SUPPORT_HANDLE.getOpaque((Stub) obj)).session();
    }

    public static Session currentSession() {
        Session session = CURRENT_SESSION.get();
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

    static boolean isUnchecked(Class<? extends Throwable> clazz) {
        return RuntimeException.class.isAssignableFrom(clazz)
            || Error.class.isAssignableFrom(clazz);
    }

    static void uncaughtException(Throwable e) {
        Thread t = Thread.currentThread();
        t.getThreadGroup().uncaughtException(t, e);
    }

    static void closeQuietly(Closeable c) {
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

    static void writeParam(Variable pipeVar, Variable paramVar) {
        Class<?> type = paramVar.classType();

        if (!type.isPrimitive()) {
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

        if (!type.isPrimitive()) {
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
