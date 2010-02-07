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
import com.amazon.carbonado.Join;
import com.amazon.carbonado.Key;
import com.amazon.carbonado.PrimaryKey;
import com.amazon.carbonado.Query;
import com.amazon.carbonado.Sequence;
import com.amazon.carbonado.Storable;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@PrimaryKey("id")
@AlternateKeys({
    @Key({"packageId", "name", "isClass"})
})
public abstract class Resource implements Storable<Resource> {
    @Sequence("Resource.id")
    public abstract long getId();
    public abstract void setId(long id);

    public abstract long getPackageId();
    public abstract void setPackageId(long id);

    public abstract String getName();
    public abstract void setName(String name);

    /**
     * If this resource represents a class, the resource name does not include
     * the ".class" extension.
     */
    public abstract boolean getIsClass();
    public abstract void setIsClass(boolean b);

    @Derived(from="package")
    public String getQualifiedName() throws FetchException {
        String packageName = getPackage().getQualifiedName();
        return packageName.length() == 0 ? getName() : packageName + '/' + getName();
    }

    @Join(internal="packageId", external="id")
    public abstract Package getPackage() throws FetchException;
    public abstract void setPackage(Package parent);

    @Join(internal="id", external="resourceId")
    public abstract Query<ResourceVersion> getVersions() throws FetchException;
}
