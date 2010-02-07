/*
 *  Copyright 2008-2009 Brian S O'Neill
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

import com.amazon.carbonado.AlternateKeys;
import com.amazon.carbonado.Derived;
import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.Index;
import com.amazon.carbonado.Indexes;
import com.amazon.carbonado.Join;
import com.amazon.carbonado.Key;
import com.amazon.carbonado.PrimaryKey;
import com.amazon.carbonado.Query;
import com.amazon.carbonado.Sequence;
import com.amazon.carbonado.Storable;

import com.amazon.carbonado.constraint.IntegerConstraint;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@PrimaryKey("id")
@AlternateKeys(@Key({"packageId", "digest", "length"}))
public abstract class PackageVersion implements Storable<PackageVersion> {
    @Sequence("PackageVersion.id")
    public abstract long getId();
    public abstract void setId(long id);

    public abstract long getPackageId();
    public abstract void setPackageId(long id);

    /**
     * Uncompressed length of all resources, in bytes.
     */
    public abstract long getLength();
    @IntegerConstraint(min=0)
    public abstract void setLength(long length);

    /**
     * Message digest of all resources. It is computed by combining all digests
     * of the resources, ordered by resource name.
     */
    public abstract byte[] getDigest();
    public abstract void setDigest(byte[] digest);

    @Derived(from="package")
    public String getQualifiedName() throws FetchException {
        return getPackage().getQualifiedName();
    }

    @Join(internal="packageId", external="id")
    public abstract Package getPackage() throws FetchException;
    public abstract void setPackage(Package pkg);

    @Join(internal="id", external="packageVersionId")
    public abstract Query<PackageVersionResource> getResources() throws FetchException;

    //@Join(internal="id", external="consumerPackageVersionId")
    //public abstract Query<PackageDependency> getDependencies() throws FetchException;
}
