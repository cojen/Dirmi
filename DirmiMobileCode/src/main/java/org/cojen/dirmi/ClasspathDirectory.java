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

package org.cojen.dirmi;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectOutput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Directory implementation which scans an ordinary classpath.
 *
 * @author Brian S O'Neill
 */
public class ClasspathDirectory implements PackageDirectory {
    /*
    public static void main(String[] args) throws Exception {
        Environment env = new Environment();
        Session[] sessions = env.newSessionPair();

        sessions[0].send(new ClasspathDirectory());
        PackageDirectory dir = (PackageDirectory) sessions[1].receive();

        for (int i=0; i<args.length - 1; i++) {
            dir = dir.subPackage(args[i]);
        }

        Pipe pipe = dir.fetchClasses(null, args[args.length - 1]);
        ResourceDescriptor desc = (ResourceDescriptor) pipe.readObject();
        System.out.println(desc);
        int b;
        while ((b = pipe.read()) >= 0) {
            System.out.print(b);
            System.out.print(' ');
        }
        System.out.println("done");
    }
    */

    private final ClasspathFile[] mPath;
    private final String mPackageName;
    private final ClasspathDirectory mParent;

    private Map<String, ResourceDescriptor> mClassMap;
    private Map<String, ResourceDescriptor> mResourceMap;

    private Map<String, Loader> mClassLoaderMap;
    private Map<String, Loader> mResourceLoaderMap;

    /**
     * Construct using the system classpath.
     */
    public ClasspathDirectory() {
        this(System.getProperty("java.class.path"));
    }

    /**
     * Construct using the given classpath which must use system path
     * separators.
     */
    public ClasspathDirectory(String classpath) {
        this(classpath, System.getProperty("path.separator"));
    }

    /**
     * Construct using the given classpath which uses the given path separator.
     */
    public ClasspathDirectory(String classpath, String separator) {
        this(splitPath(classpath, separator));
    }

    /**
     * Construct from a list of files and directories.
     */
    public ClasspathDirectory(File... path) {
        if (path == null) {
            throw new IllegalArgumentException("Classpath is null");
        }

        Set<File> files = new LinkedHashSet<File>();

        for (File file : path) {
            if (file == null) {
                continue;
            }

            try {
                try {
                    file = file.getCanonicalFile();
                } catch (Exception e) {
                    file = file.getAbsoluteFile();
                }
            } catch (SecurityException e) {
                // Leave file alone for now.
            }

            try {
                if (file.canRead()) {
                    ClasspathFile cf = new ClasspathFile(file);
                    if (cf.isDirectory() || cf.isJar()) {
                        files.add(cf);
                    }
                }
            } catch (SecurityException e) {
                // Ignore unreadable file.
            }
        }

        mPath = files.toArray(new ClasspathFile[files.size()]);
        mPackageName = "";
        mParent = null;
    }

    private ClasspathDirectory(ClasspathFile[] path,
                               String packageName,
                               ClasspathDirectory parent)
    {
        mPath = path;
        mPackageName = packageName;
        mParent = parent;
    }

    private static File[] splitPath(String classpath, String separator) {
        if (classpath == null) {
            throw new IllegalArgumentException("Classpath is null");
        }
        if (separator == null) {
            throw new IllegalArgumentException("Classpath separator is null");
        }

        List<File> files = new ArrayList<File>();

        int start = 0;
        int index;
        while (true) {
            index = classpath.indexOf(separator, start);
            String element;
            if (index < 0) {
                element = classpath.substring(start);
            } else {
                element = classpath.substring(start, index);
            }

            if (element.length() > 0) {
                files.add(new File(new String(element)));
            }

            if (index < 0) {
                break;
            }

            start = index + separator.length();
        }

        return files.toArray(new File[files.size()]);
    }

    @Override
    public PackageDirectory subPackage(String name) {
        if (name == null || name.length() == 0) {
            return this;
        }
        return new ClasspathDirectory(mPath, name, this);
    }

    @Override
    public synchronized Map<String, ResourceDescriptor> availableClasses() {
        expand();
        return mClassMap;
    }

    @Override
    public synchronized Map<String, ResourceDescriptor> availableResources() {
        expand();
        return mResourceMap;
    }

    @Override
    public Pipe fetchClasses(Pipe pipe, String... names) {
        fetchClasses(names, pipe);
        return null;
    }

    private void fetchClasses(String[] names, ObjectOutput output) {
        Map<String, ResourceDescriptor> map;
        Map<String, Loader> loaderMap;
        synchronized (this) {
            map = availableClasses();
            loaderMap = mClassLoaderMap;
        }
        fetchResources(true, map, loaderMap, names, output);
    }

    @Override
    public Pipe fetchResources(Pipe pipe, String... names) {
        fetchResources(names, pipe);
        return null;
    }

    private void fetchResources(String[] names, ObjectOutput out) {
        Map<String, ResourceDescriptor> map;
        Map<String, Loader> loaderMap;
        synchronized (this) {
            map = availableResources();
            loaderMap = mResourceLoaderMap;
        }
        fetchResources(false, map, loaderMap, names, out);
    }

    private void fetchResources(boolean gettingClasses,
                                Map<String, ResourceDescriptor> map,
                                Map<String, Loader> loaderMap,
                                String[] names, ObjectOutput out)
    {
        if (names == null) {
            names = map.keySet().toArray(new String[map.size()]);
        }

        String packagePath = buildPackagePath();
        byte[] buffer = null;

        try {
            for (String name : names) {
                ResourceDescriptor desc = map.get(name);
                Loader loader = loaderMap.get(desc.getName());

                if (loader == null) {
                    desc = null;
                }

                out.writeObject(desc);
                if (desc == null) {
                    continue;
                }

                int remaining = desc.getLength();
                if (remaining <= 0) {
                    continue;
                }

                if (buffer == null) {
                    buffer = new byte[1000];
                }

                InputStream in = loader.load(gettingClasses, packagePath, desc.getName());
                try {
                    int amt;
                    while ((amt = in.read(buffer)) > 0) {
                        if (amt <= remaining) {
                            out.write(buffer, 0, amt);
                            remaining -= amt;
                        } else {
                            // Loaded too much, so stop writing. Make sure the
                            // output has the correct number of bytes, but the
                            // digest won't match.
                            out.write(buffer, 0, remaining);
                            remaining = 0;
                            break;
                        }
                    }
                } finally {
                    try {
                        in.close();
                    } catch (IOException e) {
                        // Ignore.
                    }
                }

                if (remaining > 0) {
                    // Loaded too little. Make sure the output has the correct
                    // number of bytes, but the digest won't match.
                    Arrays.fill(buffer, (byte) 0);
                    int amt;
                    do {
                        amt = Math.max(buffer.length, remaining);
                        out.write(buffer, 0, amt);
                    } while ((remaining -= amt) > 0);
                }
            }
        } catch (IOException e) {
            // Ignore.
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                // Ignore.
            }
        }
    }

    private synchronized void expand() {
        if (mClassMap != null) {
            return;
        }

        Expander expander = new Expander();
        expander.scanClasspath();

        mClassMap = unmodifiable(expander.classMap);
        mResourceMap = unmodifiable(expander.resourceMap);

        mClassLoaderMap = unmodifiable(expander.classLoaderMap);
        mResourceLoaderMap = unmodifiable(expander.resourceLoaderMap);
    }

    private static <K, V> Map<K, V> unmodifiable(Map<? extends K, ? extends V> map) {
        if (map != null && map.size() > 0) {
            return Collections.unmodifiableMap(map);
        } else {
            return Collections.emptyMap();
        }
    }

    private String buildPackagePath() {
        StringBuilder b = new StringBuilder();
        appendPackagePath(b);
        return b.toString();
    }

    private void appendPackagePath(StringBuilder b) {
        if (mParent != null) {
            mParent.appendPackagePath(b);
        }
        if (b.length() > 0) {
            b.append('/');
        }
        b.append(mPackageName);
    }

    private class Expander {
        final Map<String, ResourceDescriptor> classMap =
            new HashMap<String, ResourceDescriptor>();
        final Map<String, ResourceDescriptor> resourceMap =
            new HashMap<String, ResourceDescriptor>();
        
        final Map<String, Loader> classLoaderMap = new HashMap<String, Loader>();
        final Map<String, Loader> resourceLoaderMap = new HashMap<String, Loader>();

        final String packagePath = buildPackagePath();

        String resourceName;
        boolean resourceIsClass;
        byte[] buffer;

        void scanClasspath() {
            for (ClasspathFile file : mPath) {
                if (file.isDirectory()) {
                    scanDirectory(file);
                } else if (file.isJar()) {
                    scanJar(file);
                }
            }
        }

        private void scanDirectory(File dir) {
            Loader loader = new DirectoryLoader(dir);

            File[] files = new File(dir, packagePath).listFiles();
            if (files != null) for (File resourceFile : files) {
                if (resourceFile.isDirectory() || !resourceFile.isFile()) {
                    continue;
                }

                if (!examineName(resourceFile.getName())) {
                    continue;
                }

                ResourceDescriptor desc;
                try {
                    desc = createDescriptor(new FileInputStream(resourceFile));
                } catch (IOException e) {
                    continue;
                }

                if (resourceIsClass) {
                    classMap.put(resourceName, desc);
                    classLoaderMap.put(resourceName, loader);
                } else {
                    resourceMap.put(resourceName, desc);
                    resourceLoaderMap.put(resourceName, loader);
                }
            }
        }

        private void scanJar(File file) {
            JarFile jf = null;
            boolean jarInUse = false;
            try {
                jf = new JarFile(file);
                Loader loader = new JarLoader(file, jf);

                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry entry = en.nextElement();

                    if (entry.isDirectory()) {
                        continue;
                    }

                    String name = entry.getName();
                    if (!name.startsWith(packagePath)) {
                        continue;
                    }

                    if (name.length() > packagePath.length() &&
                        name.charAt(packagePath.length()) != '/')
                    {
                        continue;
                    }

                    name = name.substring(packagePath.length() + 1);

                    if (name.indexOf('/') >= 0 || !examineName(name)) {
                        continue;
                    }

                    name = resourceName;

                    long length = entry.getSize();
                    if (length > Integer.MAX_VALUE) {
                        // Too large.
                        continue;
                    }

                    byte[] digest;
                    if (length < 0) {
                        // Need to scan file anyhow.
                        digest = null;
                    } else {
                        String digestStr = entry.getAttributes().getValue("SHA1-Digest");
                        if (digestStr == null) {
                            digest = null;
                        } else {
                            try {
                                digest = base64ToByteArray(digestStr);
                            } catch (IllegalArgumentException e) {
                                digest = null;
                            }
                        }
                    }

                    ResourceDescriptor desc;
                    if (digest != null) {
                        desc = new ResourceDescriptor(name, (int) length, digest);
                    } else {
                        try {
                            desc = createDescriptor(jf.getInputStream(entry));
                        } catch (IOException e) {
                            continue;
                        }
                    }

                    jarInUse = true;

                    if (resourceIsClass) {
                        classMap.put(name, desc);
                        classLoaderMap.put(name, loader);
                    } else {
                        resourceMap.put(name, desc);
                        resourceLoaderMap.put(name, loader);
                    }
                }
            } catch (IOException e) {
                // Ignore.
            } finally {
                if (jf != null && !jarInUse) {
                    try {
                        jf.close();
                    } catch (IOException e) {
                        // Ignore.
                    }
                }
            }
        }

        // Returns false if resource should be skipped.
        private boolean examineName(String name) {
            resourceName = null;
            resourceIsClass = false;

            if (name.endsWith(".java")) {
                return false;
            }

            if (name.endsWith(".class")) {
                name = name.substring(0, name.length() - 6);
                if (classMap.containsKey(name)) {
                    return false;
                }
                resourceIsClass = true;
            } else {
                if (resourceMap.containsKey(name)) {
                    return false;
                }
            }

            resourceName = name;
            return true;
        }

        private ResourceDescriptor createDescriptor(InputStream in) throws IOException {
            try {
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA-1");
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException(e);
                }

                long length = 0;

                byte[] buffer = buffer();

                int amt;
                while ((amt = in.read(buffer)) > 0) {
                    if ((length += amt) > Integer.MAX_VALUE) {
                        throw new IOException("Resource is too long");
                    }
                    md.update(buffer, 0, amt);
                }

                return new ResourceDescriptor(resourceName, (int) length, md.digest());
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }

        private byte[] buffer() {
            if (buffer == null) {
                buffer = new byte[1000];
            }
            return buffer;
        }
    }

    private static class ClasspathFile extends File {
        private final int mType;

        ClasspathFile(File file) {
            super(file.getPath());

            if (file.isDirectory()) {
                mType = 1;
            } else if (file.isFile() &&
                       file.getName().endsWith(".jar") || file.getName().endsWith(".zip")) {
                mType = 2;
            } else {
                mType = 0;
            }
        }

        @Override
        public boolean isDirectory() {
            return mType == 1;
        }

        public boolean isJar() {
            return mType == 2;
        }
    }

    private static abstract class Loader {
        InputStream load(boolean isClass, String packagePath, String name)
            throws IOException
        {
            if (isClass) {
                name = name.concat(".class");
            }
            return load(packagePath, name);
        }

        abstract InputStream load(String packagePath, String name) throws IOException;
    }

    private static class DirectoryLoader extends Loader {
        private final File mDirectory;

        DirectoryLoader(File directory) {
            mDirectory = directory;
        }

        @Override
        InputStream load(String packagePath, String name) throws IOException {
            File fullPath = new File(new File(mDirectory, packagePath), name);
            return new FileInputStream(fullPath);
        }
    }

    private static class JarLoader extends Loader {
        private final File mFile;
        private Reference<JarFile> mJarRef;

        JarLoader(File file, JarFile jf) {
            mFile = file;
            synchronized (this) {
                mJarRef = new SoftReference<JarFile>(jf);
            }
        }

        @Override
        InputStream load(String packagePath, String name) throws IOException {
            JarFile jf = jarFile();
            return jf.getInputStream(jf.getEntry(packagePath + '/' + name));
        }

        private synchronized JarFile jarFile() throws IOException {
            JarFile jf = mJarRef.get();
            if (jf == null) {
                mJarRef = new SoftReference<JarFile>(jf = new JarFile(mFile));
            }
            return jf;
        }
    }

    // Sigh, yet another base 64 decoder. This one was stolen from
    // the hidden java.util.prefs.Base64 class.
    static byte[] base64ToByteArray(String s) {
        byte[] alphaToInt = base64ToInt;
        int sLen = s.length();
        int numGroups = sLen/4;
        if (4*numGroups != sLen)
            throw new IllegalArgumentException(
                "String length must be a multiple of four.");
        int missingBytesInLastGroup = 0;
        int numFullGroups = numGroups;
        if (sLen != 0) {
            if (s.charAt(sLen-1) == '=') {
                missingBytesInLastGroup++;
                numFullGroups--;
            }
            if (s.charAt(sLen-2) == '=')
                missingBytesInLastGroup++;
        }
        byte[] result = new byte[3*numGroups - missingBytesInLastGroup];

        // Translate all full groups from base64 to byte array elements
        int inCursor = 0, outCursor = 0;
        for (int i=0; i<numFullGroups; i++) {
            int ch0 = base64toInt(s.charAt(inCursor++), alphaToInt);
            int ch1 = base64toInt(s.charAt(inCursor++), alphaToInt);
            int ch2 = base64toInt(s.charAt(inCursor++), alphaToInt);
            int ch3 = base64toInt(s.charAt(inCursor++), alphaToInt);
            result[outCursor++] = (byte) ((ch0 << 2) | (ch1 >> 4));
            result[outCursor++] = (byte) ((ch1 << 4) | (ch2 >> 2));
            result[outCursor++] = (byte) ((ch2 << 6) | ch3);
        }

        // Translate partial group, if present
        if (missingBytesInLastGroup != 0) {
            int ch0 = base64toInt(s.charAt(inCursor++), alphaToInt);
            int ch1 = base64toInt(s.charAt(inCursor++), alphaToInt);
            result[outCursor++] = (byte) ((ch0 << 2) | (ch1 >> 4));

            if (missingBytesInLastGroup == 1) {
                int ch2 = base64toInt(s.charAt(inCursor++), alphaToInt);
                result[outCursor++] = (byte) ((ch1 << 4) | (ch2 >> 2));
            }
        }
        // assert inCursor == s.length()-missingBytesInLastGroup;
        // assert outCursor == result.length;
        return result;
    }

    /**
     * Translates the specified character, which is assumed to be in the
     * "Base 64 Alphabet" into its equivalent 6-bit positive integer.
     *
     * @throw IllegalArgumentException or ArrayOutOfBoundsException if
     *        c is not in the Base64 Alphabet.
     */
    private static int base64toInt(char c, byte[] alphaToInt) {
        int result = alphaToInt[c];
        if (result < 0)
            throw new IllegalArgumentException("Illegal character " + c);
        return result;
    }

    /**
     * This array is a lookup table that translates unicode characters
     * drawn from the "Base64 Alphabet" (as specified in Table 1 of RFC 2045)
     * into their 6-bit positive integer equivalents.  Characters that
     * are not in the Base64 alphabet but fall within the bounds of the
     * array are translated to -1.
     */
    private static final byte base64ToInt[] = {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54,
        55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4,
        5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34,
        35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51
    };
}
