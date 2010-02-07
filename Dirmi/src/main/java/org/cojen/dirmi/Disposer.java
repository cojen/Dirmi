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
 * Identify a method as being a disposer of the enclosing remote object. With
 * such a method, a remote object can be freed before the local garbage
 * collector determines that it is unreachable.
 *
 * <p>When a disposer method is called, it immediately prevents all new remote
 * method inovcations on the same object from functioning. Any attempt causes a
 * {@link NoSuchObjectException} to be thrown. The actual disposer call is
 * allowed to complete normally, but any other methods running concurrently
 * might fail. Exported remote objects on the server side are not actually
 * unreferenced until the disposer implementation completes. Use the {@link
 * Ordered} annotation to ensure that the disposer doesn't run too soon.
 *
 * <pre>
 * <b>&#64;Disposer</b>
 * &#64;Asynchronous
 * void close() throws RemoteException;
 * </pre>
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Disposer {
}
