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

package org.cojen.dirmi;

import java.io.IOException;

import java.net.SocketAddress;

/**
 * Defines the default exception for indicating a remote call failure.
 *
 * @author Brian S O'Neill
 * @see RemoteFailure
 */
public class RemoteException extends IOException {
    private SocketAddress mRemoteAddress;

    public RemoteException() {
    }

    public RemoteException(String message) {
        super(message);
    }

    public RemoteException(String message, Throwable cause) {
        super(message, cause);
    }

    public RemoteException(Throwable cause) {
        super(cause);
    }

    /**
     * Returns the associated remote address, which can be null if unknown or not applicable.
     */
    public SocketAddress remoteAddress() {
        return mRemoteAddress;
    }

    /**
     * Assign the associated remote address.
     */
    public void remoteAddress(SocketAddress addr) {
        mRemoteAddress = addr;
    }

    @Override
    public String getMessage() {
        String message = super.getMessage();
        SocketAddress addr = mRemoteAddress;
        if (addr != null) {
            message = message + " (" + addr + ')';
        }
        return message;
    }
}
