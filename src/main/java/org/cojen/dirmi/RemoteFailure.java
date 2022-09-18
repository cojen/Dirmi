/*
 *  Copyright 2008-2022 Cojen.org
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

package org.cojen.dirmi;

import java.lang.annotation.*;

/**
 * Annotate a remote method or interface to specify what exception is to be used for indicating
 * a remote call failure. By default, that exception is {@link RemoteException}, and it (or a
 * superclass) must be declared to be thrown.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RemoteFailure {
    /**
     * Specify the exception to throw when the remote call fails.
     */
    Class<? extends Throwable> exception() default RemoteException.class;

    /**
     * By default, if the exception is checked, it must be declared to be thrown. Set to false
     * to allow checked exception to be thrown without being declared.
     */
    boolean declared() default true;
}
