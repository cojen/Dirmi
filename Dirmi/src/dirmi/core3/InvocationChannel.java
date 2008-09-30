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

package dirmi.core3;

import java.io.IOException;

import dirmi.Pipe;

import dirmi.io2.StreamChannel;

/**
 * Basic interface for a bidirectional method invocation I/O channel.
 *
 * @author Brian S O'Neill
 */
public interface InvocationChannel extends StreamChannel, Pipe {
    InvocationInputStream getInputStream() throws IOException;

    InvocationOutputStream getOutputStream() throws IOException;
}
