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
 * 
 *
 * @author Brian S O'Neill
 */
final class ClientPipe extends BufferedPipe {
    final ClientSession mSession;

    // Accessed by ClientSession.
    ClientPipe mPrev, mNext;

    ClientPipe(ClientSession session, InputStream in, OutputStream out) {
        super(in, out);
        mSession = session;
    }

    @Override
    public void recycle() throws IOException {
        if (isEmpty()) {
            mSession.recycle(this);
        } else {
            close();
        }
    }
}
