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

package org.cojen.dirmi.core;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Base class for ClientPipe and ServerPipe.
 *
 * @author Brian S O'Neill
 */
abstract class CorePipe extends BufferedPipe {
    // Accessed by CoreSession.
    CorePipe mConPrev, mConNext;

    // Accessed by CoreSession.
    int mVersion;

    CorePipe(InputStream in, OutputStream out) {
        super(in, out);
    }

    /**
     * @return null if not assigned yet
     */
    protected abstract CoreSession session();

    @Override
    public final void recycle() throws IOException {
        CoreSession session;
        if (isEmpty() && (session = session()) != null) {
            session.recycleConnection(this);
        } else {
            close();
        }
    }

    /**
     * @param ex can be null
     */
    @Override
    protected final void close(IOException ex) throws IOException {
        CoreSession session = session();
        if (session == null) {
            super.close(ex);
        } else {
            session.closeConnection(this);
        }
    }

    /**
     * Forcibly close the connection without attempting to remove it from the session. Should
     * only be called by CoreSession.
     */
    protected final void doClose() throws IOException {
        super.close(null);
    }
}
