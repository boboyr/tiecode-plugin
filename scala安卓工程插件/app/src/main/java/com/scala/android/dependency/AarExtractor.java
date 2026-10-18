package com.scala.android.dependency;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * AAR 解包：Android 依赖库（{@code packaging = aar}）的构件不是 jar，
 * 需要按 AAR 的结构拆成"类 / 资源 / 清单 / jni / assets"再分别喂给编译与打包任务。
 *
 * <pre>
 * foo.aar
 *  ├── classes.jar        → classpath
 *  ├── libs/*.jar         → classpath
 *  ├── AndroidManifest.xml→ 清单合并
 *  ├── res/**             → 资源合并
 *  ├── assets/**          → APK assets
 *  ├── jni/<abi>/*.so     → APK lib/<abi>
 *  └── R.txt              → 只登记（不参与编译）
 * </pre>
 *
 * <p>纯 {@code java.util.zip} 实现，不依赖宿主 API。
 */
public final class AarExtractor {

    /** 解包结果（路径都指向解包目录里真实存在的文件/目录）。 */
    public static final class Extracted {
        public File rootDir;
        public File classesJar;
        public File manifest;
        public File resDir;
        public File assetsDir;
        public File rTxt;
        public final List<File> libsJars = new ArrayList<>();
        /** abi → .so */
        public final Map<String, List<File>> jniLibs = new LinkedHashMap<>();
    }

    private AarExtractor() {
    }

    /**
     * 解包到 {@code outputDir}（已存在且完整时直接复用，便于增量构建）。
     *
     * @param aar       源 aar
     * @param outputDir 目标目录
     */
    public static Extracted extract(File aar, File outputDir) throws IOException {
        Extracted extracted = new Extracted();
        extracted.rootDir = outputDir;
        File marker = new File(outputDir, ".unpacked");
        if (!(marker.isFile() && outputDir.isDirectory())) {
            deleteRecursively(outputDir);
            if (!outputDir.mkdirs() && !outputDir.isDirectory()) {
                throw new IOException("无法创建 AAR 解包目录 " + outputDir);
            }
            unzip(aar, outputDir);
            writeText(marker, "ok");
        }
        extracted.classesJar = existing(new File(outputDir, "classes.jar"));
        extracted.manifest = existing(new File(outputDir, "AndroidManifest.xml"));
        extracted.resDir = existingDir(new File(outputDir, "res"));
        extracted.assetsDir = existingDir(new File(outputDir, "assets"));
        extracted.rTxt = existing(new File(outputDir, "R.txt"));

        File libs = new File(outputDir, "libs");
        File[] libChildren = libs.listFiles();
        if (libChildren != null) {
            for (File child : libChildren) {
                if (child.isFile() && child.getName().endsWith(".jar")) {
                    extracted.libsJars.add(child);
                }
            }
        }

        File jni = new File(outputDir, "jni");
        File[] abis = jni.listFiles();
        if (abis != null) {
            for (File abi : abis) {
                if (!abi.isDirectory()) {
                    continue;
                }
                List<File> libraries = new ArrayList<>();
                File[] files = abi.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".so")) {
                            libraries.add(file);
                        }
                    }
                }
                if (!libraries.isEmpty()) {
                    extracted.jniLibs.put(abi.getName(), libraries);
                }
            }
        }
        return extracted;
    }

    private static void unzip(File zipFile, File outputDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[16384];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                // 防 zip slip
                if (name.contains("..")) {
                    continue;
                }
                File target = new File(outputDir, name);
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("无法创建目录 " + target);
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录 " + parent);
                }
                try (InputStream input = zip.getInputStream(entry);
                     FileOutputStream output = new FileOutputStream(target)) {
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static File existing(File file) {
        return file != null && file.isFile() ? file : null;
    }

    private static File existingDir(File file) {
        return file != null && file.isDirectory() ? file : null;
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes("UTF-8"));
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
