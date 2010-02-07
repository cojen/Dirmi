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

package org.cojen.dirmi.classdb;

import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.Join;
import com.amazon.carbonado.PrimaryKey;
import com.amazon.carbonado.Storable;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@PrimaryKey({"packageVersionId", "resourceVersionId"})
public interface PackageVersionResource extends Storable<PackageVersionResource> {
    long getPackageVersionId();
    void setPackageVersionId(long id);

    long getResourceVersionId();
    void setResourceVersionId(long id);

    @Join(internal="packageVersionId", external="id")
    PackageVersion getPackageVersion() throws FetchException;
    void setPackageVersion(PackageVersion v);

    @Join(internal="resourceVersionId", external="id")
    ResourceVersion getResourceVersion() throws FetchException;
    void setResourceVersion(ResourceVersion v);
}
