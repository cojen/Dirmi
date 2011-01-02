/*
 *  Copyright 2011 Brian S O'Neill
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

package org.cojen.dirmi;

import java.lang.reflect.InvocationTargetException;

import java.rmi.Remote;

import org.cojen.dirmi.core.LocalSession;
import org.cojen.dirmi.core.Stub;

/**
 * Provides session link access for client-side and server-side remote objects.
 *
 * @author Brian S O'Neill
 * @see SessionAware
 */
public class SessionAccess {
    private SessionAccess() {
    }

    /**
     * Returns the current thread-local session link. The current link is set immediately
     * before invoking a method for a {@link SessionAware} remote object, and it is cleared
     * when the method returns. Closing the link closes the session.
     *
     * @throws IllegalStateException if no link is currently available, possibly because
     * remote object doesn't implement {@link SessionAware}
     */
    public static Link current() {
        return LocalSession.current();
    }

    /**
     * Returns the link for the session that a client-side remote object is bound to.
     *
     * @throws IllegalArgumentException if object is not a client-side remote object stub
     */
    public static Link obtain(Remote obj) {
        Throwable cause;
        try {
            if (obj instanceof Stub) {
                // Invoke magic generated static method.
                Class clazz = obj.getClass();
                return (Link) clazz.getMethod("sessionLink", clazz).invoke(null, obj);
            }
            cause = null;
        } catch (NoSuchMethodException e) {
            cause = e;
        } catch (IllegalAccessException e) {
            cause = e;
        } catch (InvocationTargetException e) {
            if ((cause = e.getCause()) == null) {
                cause = e;
            }
        }
        throw new IllegalArgumentException("Object is not a remote stub", cause);
    }
}
