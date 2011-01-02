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

import java.io.IOException;

import org.cojen.dirmi.Link;

/**
 * Ensures only public Link methods are available.
 *
 * @author Brian S O'Neill
 */
public class LinkWrapper implements Link {
    public static Link wrap(Link link) {
        if (!(link instanceof LinkWrapper)) {
            link = new LinkWrapper(link);
        }
        return link;
    }

    private final Link mLink;

    private LinkWrapper(Link link) {
        if (link == null) {
            throw new IllegalArgumentException();
        }
        mLink = link;
    }

    @Override
    public Object getLocalAddress() {
        return mLink.getLocalAddress();
    }

    @Override
    public Object getRemoteAddress() {
        return mLink.getRemoteAddress();
    }

    @Override
    public void flush() throws IOException {
        mLink.flush();
    }

    @Override
    public void close() throws IOException {
        mLink.close();
    }

    @Override
    public int hashCode() {
        return mLink.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof LinkWrapper) {
            return mLink.equals(((LinkWrapper) obj).mLink);
        }
        return false;
    }

    @Override
    public String toString() {
        return mLink.toString();
    }
}
