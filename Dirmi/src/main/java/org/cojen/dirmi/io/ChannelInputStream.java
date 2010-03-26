/*
 *  Copyright 2010 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.InputStream;
import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class ChannelInputStream extends InputStream {
    public abstract boolean isReady() throws IOException;

    public abstract int setBufferSize(int size);

    public abstract void disconnect();

    public abstract boolean inputResume();

    public abstract boolean isResumeSupported();

    abstract void inputNotify(IOExecutor executor, Channel.Listener listener);

    abstract void inputClose() throws IOException;

    abstract void inputDisconnect();
}
