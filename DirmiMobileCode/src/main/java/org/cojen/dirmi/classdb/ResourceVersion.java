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
import com.amazon.carbonado.Derived;
import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.Index;
import com.amazon.carbonado.Indexes;
import com.amazon.carbonado.Join;
import com.amazon.carbonado.Key;
import com.amazon.carbonado.Nullable;
import com.amazon.carbonado.PrimaryKey;
import com.amazon.carbonado.Query;
import com.amazon.carbonado.Sequence;
import com.amazon.carbonado.Storable;

import com.amazon.carbonado.constraint.IntegerConstraint;

import com.amazon.carbonado.lob.Blob;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@PrimaryKey("id")
@AlternateKeys(@Key({"resourceId", "digest", "length", "validationId"}))
public abstract class ResourceVersion implements Storable<ResourceVersion> {
    static final byte UNCOMPRESSED = 0;
    static final byte ZIPPED = 1;

    @Sequence("ResourceVersion.id")
    public abstract long getId();
    public abstract void setId(long id);

    public abstract long getResourceId();
    public abstract void setResourceId(long id);

    /**
     * Uncompressed length of the resource, in bytes.
     */
    public abstract int getLength();
    @IntegerConstraint(min=0)
    public abstract void setLength(int length);

    /**
     * Message digest of uncompressed resource.
     */
    public abstract byte[] getDigest();
    public abstract void setDigest(byte[] digest);

    /**
     * Is null if data is valid.
     */
    @Sequence("ResourceVersion.validationId")
    @Nullable
    public abstract Integer getValidationId();
    public abstract void setValidationId(Integer id);

    @Derived(from="validationId")
    public boolean isValid() {
        return getValidationId() == null;
    }

    public void setValid() {
        setValidationId(null);
    }

    public abstract byte getDataFormat();
    @IntegerConstraint(allowed={UNCOMPRESSED, ZIPPED})
    public abstract void setDataFormat(byte format);

    public abstract Blob getData();
    public abstract void setData(Blob data);

    @Derived(from="resource")
    public String getQualifiedName() throws FetchException {
        return getResource().getQualifiedName();
    }

    @Join(internal="resourceId", external="id")
    public abstract Resource getResource() throws FetchException;
    public abstract void setResource(Resource clazz);
}
