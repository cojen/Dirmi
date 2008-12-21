/*
 *  Copyright 2008 Brian S O'Neill
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

package dirmi;

import java.lang.annotation.*;

/**
 * Identify a method as being batched and asynchronous, which does not imply
 * non-blocking. Requests are sent to the remote endpoint, but the channel
 * is not immediately flushed. The current thread holds the same channel for
 * making additional requests until an immediate or synchronous request is
 * sent. If the current thread exits before releasing the channel, the batched
 * request is eventually sent.
 *
 * <p>A batched method must return void or a Remote object. Returning a Remote
 * object allows batched calls to be chained together.
 *
 * <pre>
 * &#64;Batched
 * void setOption(int option) throws RemoteException;
 * </pre>
 *
 * @author Brian S O'Neill
 * @see Asynchronous
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Batched {
}
