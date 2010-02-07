/*
 *  Copyright 2009 Brian S O'Neill
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

import java.io.InputStream;
import java.io.IOException;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.Map;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface PackageDirectory extends Remote {
    /**
     * Returns a sub-package for the given name.
     */
    @Batched
    PackageDirectory subPackage(String name) throws RemoteException;

    /**
     * Returns a map of all classes contained in the immediate package.
     */
    Map<String, ResourceDescriptor> availableClasses() throws RemoteException;

    /**
     * Returns a map of all non-class resources contained in the immediate
     * package.
     */
    Map<String, ResourceDescriptor> availableResources() throws RemoteException;

    /**
     * Returns a pipe which contains class data, in the same order as
     * requested. Each class is preceeded by a ResourceDescriptor, which is
     * null if not found.
     *
     * @param pipe always pass null
     * @param names class names relative to this package; pass null array to
     * retrieve all
     * @return stream of classes preceeded by ResourceDescriptors
     */
    @Asynchronous
    Pipe fetchClasses(Pipe pipe, String... names) throws RemoteException;

    /**
     * Returns a pipe which contains resource data, in the same order as
     * requested. Each resource is preceeded by a ResourceDescriptor, which is
     * null if not found.
     *
     * @param pipe always pass null
     * @param names resource names relative to this package; pass null array to
     * retrieve all
     * @return stream of resources preceeded by ResourceDescriptors
     */
    @Asynchronous
    Pipe fetchResources(Pipe pipe, String... names) throws RemoteException;
}
