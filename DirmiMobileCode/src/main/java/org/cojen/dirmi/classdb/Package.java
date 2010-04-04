/*
 *  Copyright 2008-2010 Brian S O'Neill
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
import com.amazon.carbonado.Cursor;
import com.amazon.carbonado.Derived;
import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.Join;
import com.amazon.carbonado.Key;
import com.amazon.carbonado.Nullable;
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
    @Key({"parentId", "name"}),
    @Key("qualifiedName")
})
public abstract class Package implements Storable<Package> {
    @Sequence("Package.id")
    public abstract long getId();
    public abstract void setId(long id);

    @Nullable
    public abstract Long getParentId();
    public abstract void setParentId(Long id);

    public abstract String getName();
    public abstract void setName(String name);

    @Derived(from="parent")
    public String getQualifiedName() throws FetchException {
        Package parent = getParent();
        if (parent == null) {
            return getName();
        }
        String parentName = parent.getQualifiedName();
        return parentName.length() == 0 ? getName() : parentName + '/' + getName();
    }

    @Nullable
    @Join(internal="parentId", external="id")
    public abstract Package getParent() throws FetchException;
    public abstract void setParent(Package parent);

    @Join(internal="id", external="parentId")
    public abstract Query<Package> getChildren() throws FetchException;

    @Join(internal="id", external="packageId")
    public abstract Query<Resource> getResources() throws FetchException;
}
