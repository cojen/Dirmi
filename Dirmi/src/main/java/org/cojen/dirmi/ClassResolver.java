/*
 *  Copyright 2009-2010 Brian S O'Neill
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

package org.cojen.dirmi;

import java.io.IOException;

/**
 * Defines a custom class loading scheme for a {@link Session}. Implementation
 * must be thread-safe.
 *
 * @author Brian S O'Neill
 */
public interface ClassResolver {
    /**
     * Load the class or interface for the given name. If null is returned,
     * default class resolver tries to load the class.
     *
     * @param name fully qualified class name
     */
    Class<?> resolveClass(String name) throws IOException, ClassNotFoundException;
}
