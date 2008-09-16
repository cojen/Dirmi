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

package dirmi.nio2;

import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface StreamTask extends Runnable {
    /**
     * Called when channel is closed. This method may safely block.
     */
    void closed();

    /**
     * Called when channel is closed due to an exception. This method may
     * safely block.
     */
    void closed(IOException e);
}
