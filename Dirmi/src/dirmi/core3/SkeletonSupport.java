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

package dirmi.core3;

/**
 * Object passed to a Skeleton instance in order for it to decide when channels
 * can be reused.
 *
 * @author Brian S O'Neill
 */
public interface SkeletonSupport {
    /**
     * Called after channel usage is finished and can be reused for receiving
     * new requests. This method should not throw any exception.
     *
     * @param synchronous pass true for synchronous method
     */
    void finished(InvocationChannel channel, boolean synchronous);
}
