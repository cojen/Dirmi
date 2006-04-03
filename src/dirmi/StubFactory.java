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

import java.rmi.Remote;
import java.rmi.RemoteException;

import dirmi.info.RemoteInfo;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface StubFactory<R extends Remote> {
    /**
     * @return remote type supported by this stub factory
     */
    Class<R> getRemoteType();

    /**
     * @return class that implements stub
     */
    Class<? extends R> getStubClass();

    /**
     * @param objectID ID of remote object
     * @param support for invoking remote methods
     */
    R createStub(int objectID, StubSupport support);

    /**
     * @return true if given object is known to be a stub
     */
    boolean isStub(R stub);

    /**
     * @return true if stub disposed, false if stub was already
     * disposed or is unknown
     */
    boolean dispose(R stub) throws RemoteException;
}
