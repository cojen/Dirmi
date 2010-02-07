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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.cojen.classfile.ClassFile;
import org.cojen.classfile.CodeAssembler;
import org.cojen.classfile.CodeDisassembler;
import org.cojen.classfile.ConstantInfo;
import org.cojen.classfile.ConstantPool;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.NullCodeAssembler;
import org.cojen.classfile.TypeDesc;

import org.cojen.classfile.constant.ConstantClassInfo;

import com.amazon.carbonado.Cursor;
import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.PersistException;
import com.amazon.carbonado.Query;
import com.amazon.carbonado.Repository;
import com.amazon.carbonado.RepositoryException;
import com.amazon.carbonado.Storage;
import com.amazon.carbonado.Transaction;
import com.amazon.carbonado.Trigger;

import com.amazon.carbonado.lob.ByteArrayBlob;

import com.amazon.carbonado.cursor.SortedCursor;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ResourceManager {
    private final Repository mRepo;

    private final Storage<Resource> mResourceStorage;
    private final Storage<ResourceVersion> mResourceVersionStorage;
    private final Storage<ResourceDependency> mResourceDependencyStorage;

    private final Storage<Package> mPackageStorage;
    private final Storage<PackageVersion> mPackageVersionStorage;
    private final Storage<PackageVersionResource> mPackageVersionResourceStorage;

    public ResourceManager(Repository repo) throws RepositoryException {
        mRepo = repo;

        mResourceStorage = repo.storageFor(Resource.class);
        mResourceStorage.addTrigger(ResourceTrigger.THE);

        mResourceVersionStorage = repo.storageFor(ResourceVersion.class);

        mResourceDependencyStorage = repo.storageFor(ResourceDependency.class);

        mPackageStorage = repo.storageFor(Package.class);
        mPackageStorage.addTrigger(PackageTrigger.THE);

        mPackageVersionStorage = repo.storageFor(PackageVersion.class);

        mPackageVersionResourceStorage = repo.storageFor(PackageVersionResource.class);
    }

    public PackageVersion addPackageVersion(String packageName, ResourceSpec[] resourceSpecs)
        throws RepositoryException, RejectedResourceException
    {
        resourceSpecs = resourceSpecs.clone();
        Arrays.sort(resourceSpecs);

        long length = 0;
        MessageDigest md = createMessageDigest();

        ResourceSpec last = null;
        for (ResourceSpec resourceSpec : resourceSpecs) {
            if (last != null && last.compareTo(resourceSpec) == 0) {
                throw new RejectedResourceException("Duplicate resource", last);
            }
            length += resourceSpec.getLength();
            md.update(resourceSpec.getDigest());
            last = resourceSpec;
        }

        byte[] digest = md.digest();

        // FIXME
        return null;
    }

    /**
     * @param dataStream used only if resource version doesn't exist; stream is
     * not closed by this method
     * @throws IOException thrown by dataStream
     */
    public ResourceVersion addResourceVersion(String packageName, ResourceSpec resourceSpec,
                                              DataFormat format, InputStream dataStream)
        throws RepositoryException, RejectedResourceException, IOException
    {
        packageName = packageName.replace('.', '/');

        final String resourceName = resourceSpec.getName();
        final boolean isClass = resourceSpec.isClass();
        final int length = resourceSpec.getLength();
        final byte[] digest = resourceSpec.getDigest();

        Resource resource = addResource(packageName, resourceName, isClass);
        ResourceVersion rv = addResourceVersion(resource, length, digest, ResourceVersion.ZIPPED);

        if (rv.isValid()) {
            return rv;
        }

        if (format == DataFormat.ZIPPED) {
            dataStream = new GZIPInputStream(dataStream);
        }

        MessageDigest md = createMessageDigest();
        dataStream = new DigestInputStream(dataStream, md);

        TransferStream transfer;
        ClassFile cf;

        OutputStream out = rv.getData().openOutputStream();
        try {
            out = new GZIPOutputStream(out);
            if (isClass) {
                transfer = new TransferStream(dataStream, out);
                cf = ClassFile.readFrom(transfer);
            } else {
                // Only compute actual length and digest.
                transfer = new TransferStream(dataStream, out);
                cf = null;
                byte[] buffer = new byte[1000];
                while ((transfer.read(buffer)) > 0) {}
            }
        } finally {
            out.close();
        }

        if (transfer.count != length) {
            rv.delete();
            throw new RejectedResourceException("Incorrect length", resourceSpec);
        }

        if (!Arrays.equals(md.digest(), digest)) {
            rv.delete();
            throw new RejectedResourceException("Incorrect digest", resourceSpec);
        }

        if (cf != null) {
            String qualifiedName;
            if (packageName == null || packageName.length() == 0) {
                qualifiedName = resourceName;
            } else {
                qualifiedName = packageName.replace('/', '.') + '.' + resourceName;
            }
            if (!cf.getClassName().equals(qualifiedName)) {
                rv.delete();
                throw new RejectedResourceException
                    ("Incorrect class name: " + cf.getClassName() + " != " + qualifiedName,
                     resourceSpec);
            }
        }

        Transaction txn = mRepo.enterTransaction();
        try {
            txn.setForUpdate(true);

            ResourceVersion validVersion = mResourceVersionStorage.prepare();
            validVersion.setResource(resource);
            validVersion.setLength(length);
            validVersion.setDigest(digest);
            validVersion.setValid();

            if (validVersion.tryLoad()) {
                rv.delete();
                txn.commit();
                return validVersion;
            }

            if (cf != null) {
                addDependencies(rv, cf);
            }

            rv.setValid();
            rv.update();

            txn.commit();
            return rv;
        } finally {
            txn.exit();
        }
    }

    private ResourceVersion addResourceVersion(Resource resource,
                                               int length, byte[] digest,
                                               byte format)
        throws RepositoryException
    {
        Transaction txn = mRepo.enterTransaction();
        try {
            txn.setForUpdate(true);

            ResourceVersion version = mResourceVersionStorage.prepare();
            version.setResource(resource);
            version.setLength(length);
            version.setDigest(digest);
            version.setValid();

            if (version.tryLoad()) {
                return version;
            }

            version = mResourceVersionStorage.prepare();
            version.setResource(resource);
            version.setLength(length);
            version.setDigest(digest);
            version.setDataFormat(format);
            version.setData(new ByteArrayBlob(1));
            version.insert();

            txn.commit();
            return version;
        } finally {
            txn.exit();
        }
    }

    private Resource addClassResource(String qualifiedName) throws RepositoryException {
        int index = qualifiedName.lastIndexOf('.');
        if (index < 0) {
            return addResource("", qualifiedName, true);
        }
        return addResource(qualifiedName.substring(0, index), 
                           qualifiedName.substring(index + 1), true);
    }

    private Resource addResource(String packageName, String resourceName, boolean isClass)
        throws RepositoryException
    {
        Package pkg = addPackage(packageName);
        return addResource(pkg, resourceName, isClass);
    }

    private Resource addResource(Package pkg, String name, boolean isClass)
        throws RepositoryException
    {
        Transaction txn = mRepo.enterTransaction();
        try {
            txn.setForUpdate(true);

            Resource resource = mResourceStorage.prepare();
            resource.setPackage(pkg);
            resource.setName(name);
            resource.setIsClass(isClass);

            if (resource.tryLoad()) {
                return resource;
            }

            resource.insert();

            txn.commit();
            return resource;
        } finally {
            txn.exit();
        }
    }

    private Package addPackage(String qualifiedName) throws RepositoryException {
        Transaction txn = mRepo.enterTransaction();
        try {
            txn.setForUpdate(true);

            Package pkg = mPackageStorage.query("qualifiedName = ?")
                .with(qualifiedName).tryLoadOne();

            if (pkg != null) {
                return pkg;
            }

            pkg = mPackageStorage.prepare();

            if (qualifiedName.length() == 0) {
                pkg.setName("");
                pkg.insert();
                txn.commit();
                return pkg;
            }

            String parentName, name;

            int index = qualifiedName.lastIndexOf('/');
            if (index <= 0) {
                parentName = "";
                name = qualifiedName;
            } else {
                parentName = qualifiedName.substring(0, index);
                name = qualifiedName.substring(index + 1);
            }

            Package parent = addPackage(parentName);

            pkg.setParent(parent);
            pkg.setName(name);
            pkg.insert();

            txn.commit();
            return pkg;
        } finally {
            txn.exit();
        }
    }

    private void addDependencies(ResourceVersion rv, ClassFile cf) throws RepositoryException {
        ConstantPool cp = cf.getConstantPool();
        for (ConstantInfo info : cp.getAllConstants()) {
            if (info instanceof ConstantClassInfo) {
                TypeDesc type = ((ConstantClassInfo) info).getType();
                if (type.isArray()) {
                    type = type.getRootComponentType();
                }
                if (!type.isPrimitive()) {
                    addDependency(rv, addClassResource(type.getFullName()));
                }
            }
        }

        // Add depenencies discovered via runtime patterns like Class.forName.

        if (cf.getInitializer() != null) {
            addDependencies(rv, cf.getInitializer());
        }

        for (MethodInfo mi : cf.getConstructors()) {
            addDependencies(rv, mi);
        }

        for (MethodInfo mi : cf.getMethods()) {
            addDependencies(rv, mi);
        }
    }

    private void addDependencies(final ResourceVersion rv, final MethodInfo mi)
        throws RepositoryException
    {
        if (mi.getCodeAttr() == null) {
            return;
        }

        // Search for use of Class.forName("literal") and old-style class
        // literals.

        final boolean oldStyle = mi.getClassFile().getMajorVersion() < 49;

        CodeDisassembler disassembler = new CodeDisassembler(mi);
        CodeAssembler assembler = new NullCodeAssembler(mi) {
            private String mStringConstant;
            private int mStringLoc = -1;

            @Override
            public void loadConstant(String value) {
                super.loadConstant(value);
                mStringConstant = value;
                mStringLoc = getInstructionsSeen();
            }

            @Override
            public void invokeStatic(String methodName, TypeDesc ret, TypeDesc[] params) {
                super.invokeStatic(methodName, ret, params);

                if (!oldStyle || (mStringLoc + 1) != getInstructionsSeen()) {
                    return;
                }

                if (methodName.equals("class$") &&
                    ret == TypeDesc.forClass(Class.class) && params.length == 1 &&
                    params[0] == TypeDesc.STRING)
                {
                    // Found use of old-style class literal.
                    try {
                        addDependency(rv, addClassResource(mStringConstant));
                    } catch (RepositoryException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            @Override
            public void invokeStatic(String className,
                                     String methodName, TypeDesc ret, TypeDesc[] params)
            {
                super.invokeStatic(className, methodName, ret, params);

                if ((mStringLoc + 1) != getInstructionsSeen()) {
                    return;
                }
                
                if (className.equals("java.lang.Class") && methodName.equals("forName") &&
                    ret == TypeDesc.forClass(Class.class) && params.length == 1 &&
                    params[0] == TypeDesc.STRING)
                {
                    // Found use of Class.forName("literal").
                    try {
                        addDependency(rv, addClassResource(mStringConstant));
                    } catch (RepositoryException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };

        try {
            disassembler.disassemble(assembler);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RepositoryException) {
                throw (RepositoryException) cause;
            }
            throw e;
        }
    }

    private void addDependency(ResourceVersion rv, Resource consumed) throws RepositoryException {
        ResourceDependency dep = mResourceDependencyStorage.prepare();
        dep.setConsumerResourceVersion(rv);
        dep.setConsumedResource(consumed);
        dep.tryInsert();
    }

    private MessageDigest createMessageDigest() throws RejectedResourceException {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RejectedResourceException(e);
        }
    }

    private static class PackageTrigger extends Trigger<Package> {
        static final PackageTrigger THE = new PackageTrigger();

        private PackageTrigger() {
        }

        @Override
        public void afterDelete(Package pkg, Object state) throws PersistException {
            // Cascade delete.
            try {
                //pkg.getDependencies().deleteAll();
                //pkg.getVersions().deleteAll();
                pkg.getResources().deleteAll();
                pkg.getChildren().deleteAll();
            } catch (FetchException e) {
                throw e.toPersistException();
            }
        }
    }

    private static class ResourceTrigger extends Trigger<Resource> {
        static final ResourceTrigger THE = new ResourceTrigger();

        private ResourceTrigger() {
        }

        @Override
        public void afterDelete(Resource resource, Object state) throws PersistException {
            // Cascade delete.
            try {
                resource.getVersions().deleteAll();
            } catch (FetchException e) {
                throw e.toPersistException();
            }
        }
    }
}
