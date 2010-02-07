/*
 *  Copyright 2008-2010 Brian S O'Neill
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

import java.lang.annotation.*;
import java.rmi.RemoteException;

/**
 * Annotate a remote method or interface to specify what exception is to be
 * used to indicate a remote call failure. By default, it is {@link
 * RemoteException}, and it must be declared to be thrown.
 *
 * <pre>
 * <b>&#64;RemoteFailure(exception=SQLException.class)</b>
 * int executeUpdate(String sql) throws SQLException;
 * </pre>
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RemoteFailure {
    /**
     * Specify the exception to throw when the remote call fails. If not {@link
     * RemoteException} (or a superclass), then it must support exception
     * chaining. A public constructor must exist which accepts a single
     * argument of {@code RemoteException} or a superclass.
     */
    Class<? extends Throwable> exception() default RemoteException.class;

    /**
     * By default, if the exception is checked, it must be declared to be
     * thrown. Set to false to allow checked exception to be thrown without
     * being declared.
     */
    boolean declared() default true;
}
