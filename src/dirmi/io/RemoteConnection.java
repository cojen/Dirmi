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

package dirmi.io;

import java.io.IOException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import dirmi.core.Identifier;

/**
 * Basic interface for a bidirectional remote I/O connection.
 *
 * @author Brian S O'Neill
 */
public interface RemoteConnection extends Connection {
    RemoteInputStream getInputStream() throws IOException;

    RemoteOutputStream getOutputStream() throws IOException;

    /**
     * Dispose a remotely identified object, rendering it unusable for future
     * remote calls. Usually objects need not be explicitly disposed, since the
     * local and remote garbage collectors do so automatically.
     */
    void dispose(Identifier id) throws RemoteException;
}
