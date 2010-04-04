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

import java.io.*;

import java.util.*;

import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import java.util.zip.ZipException;

import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.amazon.carbonado.*;
import com.amazon.carbonado.repo.sleepycat.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class Test {
    public static void main(String[] args) throws Exception {
        BDBRepositoryBuilder bob = new BDBRepositoryBuilder();
        bob.setName("test");
        bob.setEnvironmentHome(args[0]);
        bob.setTransactionWriteNoSync(true);
        Repository repo = bob.build();

        Test t = new Test(repo);

        if (args.length <= 2) {
            t.scan(new File(args[1]), null);
        } else {
            String packageName = args[2].replace('.', '/');
            t.scan(new File(args[1]), packageName);
        }
    }

    private final ResourceManager rm;

    private Test(Repository repo) throws RepositoryException {
        rm = new ResourceManager(repo);
    }

    /**
     * @param packageName pass null to scan for jar files only
     */
    public void scan(final File file, String packageName) throws Exception {
        if (file.isDirectory()) {
            File[] list = file.listFiles();
            if (list != null) {
                for (File f : list) {
                    String sub;
                    if (packageName == null) {
                        sub = null;
                    } else if (f.isFile()) {
                        sub = packageName;
                    } else if (packageName.length() == 0) {
                        sub = f.getName();
                    } else {
                        sub = packageName + '/' + f.getName();
                    }
                    scan(f, sub);
                }
            }
            return;
        }

        if (!file.isFile()) {
            return;
        }

        String name = file.getName();

        if (name.endsWith(".java") || name.endsWith("~")) {
            return;
        }

        if (name.endsWith(".jar")) {
            final JarFile jf;
            try {
                jf = new JarFile(file);
            } catch (ZipException e) {
                System.out.println(e);
                return;
            }
            try {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    final JarEntry entry = en.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String entryName = entry.getName();

                    boolean isClass;

                    if (entryName.endsWith(".class")) {
                        entryName = entryName.substring(0, entryName.length() - 6);
                        isClass = true;
                    } else {
                        isClass = false;
                    }

                    char pathChar = 0;

                    int index = entryName.lastIndexOf('/');
                    if (index >= 0) {
                        pathChar = '/';
                    } else {
                        index = entryName.lastIndexOf('\\');
                        if (index >= 0) {
                            pathChar = '\\';
                        }
                    }

                    String entryPackageName;

                    if (pathChar == 0) {
                        entryPackageName = "";
                    } else {
                        entryPackageName = entryName.substring(0, index).replace(pathChar, '/');
                        entryName = entryName.substring(index + 1);
                    }

                    addResourceVersion(entryPackageName, entryName, isClass, new InputFactory() {
                        public InputStream open() throws IOException {
                            return jf.getInputStream(entry);
                        }
                    });
                }
            } finally {
                jf.close();
            }
        } else if (packageName != null) {
            boolean isClass;
            if (name.endsWith(".class")) {
                name = name.substring(0, name.length() - 6);
                isClass = true;
            } else {
                isClass = false;
            }

            addResourceVersion(packageName, name, isClass, new InputFactory() {
                public InputStream open() throws IOException {
                    return new FileInputStream(file);
                }
            });
        }
    }

    private void addResourceVersion(String packageName, String resourceName, boolean isClass,
                                    InputFactory inputFactory)
        throws Exception
    {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }

        int length = 0;
        try {
            InputStream in = new DigestInputStream(inputFactory.open(), md);
            byte[] buf = new byte[1000];
            int amt;
            while ((amt = in.read(buf)) > 0) {
                length += amt;
            }
            in.close();
        } catch (SecurityException e) {
            System.out.println(e);
            return;
        }

        ResourceSpec resourceSpec = new ResourceSpec(resourceName, isClass, length, md.digest());
        InputStream dataStream = new BufferedInputStream(inputFactory.open());

        try {
            ResourceVersion rv = rm.addResourceVersion
                (packageName, resourceSpec, DataFormat.UNCOMPRESSED, dataStream);

            System.out.println(rv.getResource().getQualifiedName());
        } catch (Exception e) {
            System.out.println(e);
        } finally {
            dataStream.close();
        }
    }

    private static interface InputFactory {
        InputStream open() throws IOException;
    }
}
