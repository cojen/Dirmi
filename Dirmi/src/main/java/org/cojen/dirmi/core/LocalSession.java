/*
 *  Copyright 2011 Brian S O'Neill
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

package org.cojen.dirmi.core;

import org.cojen.dirmi.Link;

/**
 * Thread-local session used by {@link org.cojen.dirmi.SessionAccess}.
 *
 * @author Brian S O'Neill
 */
public class LocalSession extends ThreadLocal<Link> {
    static final LocalSession THE = new LocalSession();

    private LocalSession() {
    }

    public static Link tryCurrent() {
        return THE.get();
    }

    @Override
    public Link get() {
        Link link = super.get();
        if (link == null) {
            return null;
        }
        if (!(link instanceof LinkWrapper)) {
            // Lazily wrap it.
            link = LinkWrapper.wrap(link);
            set(link);
        }
        return link;
    }
}
