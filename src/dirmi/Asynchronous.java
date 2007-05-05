/*
 *  Copyright 2006 Brian S O'Neill
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
 * Identify a method as being asynchronous, which does not imply
 * non-blocking. It merely means that the caller does not wait for the remote
 * method to finish. An asynchronous method will likely block if the network
 * layer is backed up.
 *
 * <p>An asynchronous method must return void, and it may not declare any
 * exceptions other than {@link java.rmi.RemoteException}.
 *
 * <p>Asynchronous methods should be used with caution or else they can
 * overwhelm the server with active threads. Asynchronous methods should either
 * run quickly or be called infrequently to prevent problems. Specify a permit
 * value to limit the number of outstanding calls.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Asynchronous {
    /**
     * Specify the maximum number of outstanding asynchronous calls before the
     * client waits for completion. This is a flow control mechanism which
     * prevents overwhelming the server with active threads. By default, there
     * is no limit.
     *
     * <p>A thread blocked waiting for a permit cannot be interrupted unless
     * the asynchronous method declares throwing InterruptedException.
     */
    int permits() default -1;

    /**
     * If asynchronous call has a limited number of permits, set fair to true
     * to guarantee first-in first-out granting of permits under contention.
     */
    boolean fair() default false;
}
