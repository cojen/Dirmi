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

package org.cojen.dirmi;

import java.lang.annotation.*;

/**
 * Ensure that invocation of method is ordered with respect to other remote
 * methods in the same object. By default, remote methods are executed on the
 * remote endpoint in any order. Ordered method execution has the greatest
 * impact on {@link Asynchronous} methods. In addition, it can reduce the
 * amount of concurrent thread execution.
 *
 * <p><pre>
 * <b>&#64;Ordered</b>
 * &#64;Asynchronous
 * void logInfo(String message, DateTime timestamp) throws RemoteException;
 * </pre>
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Ordered {
}
