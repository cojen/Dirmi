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

package org.cojen.dirmi.util;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestWrapper {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestWrapper.class.getName());
    }

    @Test
    public void base1() {
        Base1 base = Wrapper.from(Base1.class, Impl.class).wrap(new Impl());
        assertEquals("echo:hello", base.echo("hello"));
    }

    @Test
    public void base2() {
        Base2 base = Wrapper.from(Base2.class, Impl.class).wrap(new Impl());
        assertEquals("echo:hello", base.echo("hello"));
    }

    @Test
    public void base3() {
        Impl impl = new Impl();
        Base3 base = Wrapper.from(Base3.class, Impl.class).wrap(impl);
        assertEquals(impl, base.mImpl);
        assertEquals("echo:hello", base.echo("hello"));
    }

    @Test
    public void base4() throws Exception {
        Impl impl = new Impl();
        Base4 base = Wrapper.from(Base4.class, Impl.class)
            .getConstructor(Impl.class, int.class).newInstance(impl, 5);
        assertEquals(impl, base.mImpl);
        assertEquals(5, base.mParam);
        assertEquals("echo:hello", base.echo("hello"));
    }

    public static interface Base1 {
        public String echo(String str);
    }

    public static abstract class Base2 {
        public abstract String echo(String str);
    }

    public static abstract class Base3 {
        public final Impl mImpl;

        public Base3(Impl impl) {
            mImpl = impl;
        }

        public abstract String echo(String str);
    }

    public static abstract class Base4 {
        public final Impl mImpl;
        public final int mParam;

        public Base4(Impl impl, int param) {
            mImpl = impl;
            mParam = param;
        }

        public abstract String echo(String str);
    }

    public static class Impl {
        public String echo(String str) {
            return "echo:" + str;
        }
    }
}
