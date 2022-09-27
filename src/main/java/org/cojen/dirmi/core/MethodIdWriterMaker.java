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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;

import org.cojen.dirmi.Pipe;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class MethodIdWriterMaker {
    private static final SoftCache<IntArrayKey, MethodIdWriter> cCache = new SoftCache<>();

    /**
     * @param original server-side RemoteInfo that a Stub is coded to use
     * @param current server-side RemoteInfo provided by the remote side
     */
    static MethodIdWriter writerFor(RemoteInfo original, RemoteInfo current) {
        int[] mapping = original.methodIdMap(current);

        var key = new IntArrayKey(mapping);
        var writer = cCache.get(key);
        if (writer == null) synchronized (cCache) {
            writer = cCache.get(key);
            if (writer == null) {
                writer = makeWriter(mapping);
                cCache.put(key, writer);
            }
        }

        return writer;
    }

    private static MethodIdWriter makeWriter(int[] mapping) {
        ClassMaker cm = ClassMaker.begin(null, MethodHandles.lookup())
            .implement(MethodIdWriter.class).final_();
        cm.addConstructor();
        
        MethodMaker mm = cm.addMethod(null, "writeMethodId", Pipe.class, int.class).public_();

        if (mapping.length > 0) {
            var defaultLabel = mm.label();
            var cases = new int[mapping.length];
            var labels = new Label[mapping.length];

            int maxCurrentId = 0;
            int numMatches = 0;

            for (int i=0; i<mapping.length; i++) {
                cases[i] = i;
                int idCurrent = mapping[i];
                if (idCurrent == Integer.MIN_VALUE) {
                    labels[i] = defaultLabel;
                } else {
                    labels[i] = mm.label();
                    maxCurrentId = Math.max(maxCurrentId, idCurrent);
                    numMatches++;
                }
            }

            if (numMatches > 0) {
                var pipeVar = mm.param(0);
                var idOriginalVar = mm.param(1);

                Label writeLabel = mm.label();
                var idCurrentVar = mm.var(int.class);

                if (numMatches == 1) {
                    for (int i=0;; i++) {
                        int idCurrent = mapping[i];
                        if (idCurrent != Integer.MIN_VALUE) {
                            idOriginalVar.ifNe(cases[i], defaultLabel);
                            idCurrentVar.set(idCurrent);
                            break;
                        }
                    }
                } else {
                    idOriginalVar.switch_(defaultLabel, cases, labels);

                    for (int i=0; i<mapping.length; i++) {
                        int idCurrent = mapping[i];
                        if (idCurrent != Integer.MIN_VALUE) {
                            labels[i].here();
                            idCurrentVar.set(idCurrent);
                            mm.goto_(writeLabel);
                        }
                    }

                    writeLabel.here();
                }

                if (maxCurrentId < 256) {
                    pipeVar.invoke("writeByte", idCurrentVar);
                } else if (maxCurrentId < 65536) {
                    pipeVar.invoke("writeShort", idCurrentVar);
                } else {
                    // Impossible case.
                    pipeVar.invoke("writeInt", idCurrentVar);
                }

                mm.return_();

                defaultLabel.here();
            }
        }

        mm.new_(NoSuchMethodError.class, "Unimplemented on the remote side").throw_();

        MethodHandles.Lookup lookup = cm.finishHidden();

        try {
            return (MethodIdWriter) lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class)).invoke();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }
}
