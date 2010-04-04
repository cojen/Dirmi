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

package org.cojen.dirmi.classdb;

import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.Index;
import com.amazon.carbonado.Indexes;
import com.amazon.carbonado.Join;
import com.amazon.carbonado.PrimaryKey;
import com.amazon.carbonado.Storable;

/**
 * Describes the dependency between a consumed resource and the version of the
 * consuming resource. A fully constructed dependency graph links versioned
 * resources together, and it may contain cycles.
 *
 * <p>The asymmetric design of the stored relationship captures the fact that a
 * specific version of a resource will always depend on the same set of
 * resources. At deployment time the actual resource versions consumed may
 * differ.
 *
 * @author Brian S O'Neill
 */
@PrimaryKey({"consumedResourceId", "consumerResourceVersionId"})
@Indexes(@Index("consumerResourceVersionId"))
public interface ResourceDependency extends Storable<ResourceDependency> {
    long getConsumedResourceId();
    void setConsumedResourceId(long id);

    long getConsumerResourceVersionId();
    void setConsumerResourceVersionId(long id);

    @Join(internal="consumedResourceId", external="id")
    Resource getConsumedResource() throws FetchException;
    void setConsumedResource(Resource pkg);

    @Join(internal="consumerResourceVersionId", external="id")
    ResourceVersion getConsumerResourceVersion() throws FetchException;
    void setConsumerResourceVersion(ResourceVersion v);
}
