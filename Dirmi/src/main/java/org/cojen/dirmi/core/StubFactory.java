/*
 *  Copyright 2006-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.rmi.Remote;

/**
 * Produces new Stub instances for client-side Remote objects. A Stub instance
 * marshalls requests to a remote {@link Skeleton} which in turn calls the real
 * method. Any response is marshalled back for the Stub to decode.
 *
 * @author Brian S O'Neill
 * @see StubFactoryGenerator
 */
public interface StubFactory<R extends Remote> {
    /**
     * @param support for invoking remote methods
     * @return object which also implements {@link Stub}
     */
    R createStub(StubSupport support);
}
