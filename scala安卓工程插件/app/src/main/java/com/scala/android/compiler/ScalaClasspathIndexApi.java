package com.scala.android.compiler;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Builds a safe simple-name index from compiler classpath jars. */
public final class ScalaClasspathIndexApi {
    private ScalaClasspathIndexApi() {
    }

    public static Map<String, String> uniqueTypes(List<File> classpath) {
        if (classpath == null) return Collections.emptyMap();
        Map<String, String> unique = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (File file : classpath) {
            if (file == null || !file.exists()) continue;
            if (file.isDirectory()) {
                indexDirectory(file, file, unique, ambiguous);
            } else if (file.getName().endsWith(".jar") || file.getName().endsWith(".zip")) {
                indexArchive(file, unique, ambiguous);
            }
        }
        return unique;
    }

    private static void indexArchive(File file, Map<String, String> unique, Set<String> ambiguous) {
        try (JarFile jar = new JarFile(file)) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                addTypeEntry(entries.nextElement().getName(), unique, ambiguous);
            }
        } catch (Exception ignored) {
            // A broken optional classpath entry must not disable editing.
        }
    }

    private static void indexDirectory(File root, File directory,
                                       Map<String, String> unique, Set<String> ambiguous) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                indexDirectory(root, child, unique, ambiguous);
            } else {
                String relative = root.toPath().relativize(child.toPath()).toString();
                addTypeEntry(relative.replace(File.separatorChar, '/'), unique, ambiguous);
            }
        }
    }

    private static void addTypeEntry(String name, Map<String, String> unique, Set<String> ambiguous) {
        if (!isPublicTypeEntry(name)) return;
        String qualified = name.substring(0, name.length() - 6).replace('/', '.');
        int separator = qualified.lastIndexOf('.');
        String simple = separator < 0 ? qualified : qualified.substring(separator + 1);
        if (ambiguous.contains(simple)) return;
        String previous = unique.putIfAbsent(simple, qualified);
        if (previous != null && !previous.equals(qualified)) {
            unique.remove(simple);
            ambiguous.add(simple);
        }
    }

    private static boolean isPublicTypeEntry(String name) {
        if (!name.endsWith(".class") || name.indexOf('$') >= 0) return false;
        if (name.startsWith("META-INF/versions/")) return false;
        return !name.endsWith("module-info.class") && !name.endsWith("package-info.class");
    }
}
