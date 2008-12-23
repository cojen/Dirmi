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

package dirmi.core;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ExceptionUtils {
    /**
     * Augments the stack trace of the given exception with the local stack
     * trace. Useful for rethrowing exceptions from asynchronous callbacks.
     *
     * @param message separator message in trace
     */
    public static void addLocalTrace(Throwable e) {
        String message = "--- thread transfer ---";

        StackTraceElement[] original = e.getStackTrace();
        StackTraceElement[] local = new Exception().getStackTrace();
        if (local.length == 0) {
            return;
        }

        StackTraceElement[] merged = new StackTraceElement[local.length + original.length];

        // Append original.
        System.arraycopy(original, 0, merged, 0, original.length);

        // Append separator.
        merged[original.length] = new StackTraceElement(message, "", null, -1);

        // Append local trace and omit this method.
        System.arraycopy(local, 1, merged, original.length + 1, local.length - 1);

        e.setStackTrace(merged);
    }
}
