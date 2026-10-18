package com.scala.android.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * APK 打包器：把「aapt2 link 的产物（res.ap_）+ dex + .so + java resources」
 * 组装成一个 APK。
 *
 * <h3>为什么不直接用宿主的 {@code Tool.APK_GENERATOR}</h3>
 * 结绳本体里真正在用的其实是它自己写在 {@code GenerateAPKTask} 里的那套
 * {@code JarOutputStream} 打包逻辑（{@code Tool.APK_GENERATOR} 那条路在本体构建里
 * 并没有被使用）。本插件原来走 {@code ApkGenerator}，在真机上卡在"不产出文件"上，
 * 所以改成和本体同样的做法：<b>打包自己实现，签名交给宿主 apksigner</b>。
 *
 * <h3>打包规则（与 Android 的约定一致）</h3>
 * <table>
 *   <tr><td>{@code resources.arsc}</td><td><b>不压缩</b>（Android 的要求）</td></tr>
 *   <tr><td>{@code lib/<abi>/*.so}</td><td><b>不压缩</b>（可直接 mmap 加载）</td></tr>
 *   <tr><td>其余 res / AndroidManifest.xml / classes*.dex</td><td>压缩</td></tr>
 * </table>
 * 不写 META-INF：v1/v2/v3 签名由 {@code Tool.APK_SIGNER}（apksigner）统一负责。
 *
 * <p>纯 Java 实现，可以在普通 JVM 上单独跑（见 {@code tests/.../ApkPackerHarness}）。
 */
public final class ApkPacker {

    public interface Log {
        void line(String message);
    }

    /** 不压缩的条目名。 */
    private static final Set<String> STORED_NAMES = new HashSet<>();
    /** 不压缩的目录前缀。 */
    private static final String[] STORED_PREFIXES = {"lib/"};

    static {
        STORED_NAMES.add("resources.arsc");
    }

    private ApkPacker() {
    }

    /**
     * 打包。
     *
     * @param output     目标 APK（会被覆盖）
     * @param resApk     aapt2 link 产出的 {@code res.ap_}（含 res/ 与 AndroidManifest.xml）
     * @param dexFiles   {@code classes.dex} / {@code classes2.dex} …（顺序即写入顺序）
     * @param nativeLibs abi → .so 列表（写成 {@code lib/<abi>/<name>}）
     * @param javaResDirs 放到 APK 根目录的 java resources 目录（可为空）
     * @return 产物大小
     */
    public static long pack(File output, File resApk, List<File> dexFiles,
                            Map<String, List<File>> nativeLibs, List<File> javaResDirs,
                            Log log) throws IOException {
        if (resApk == null || !resApk.isFile()) {
            throw new IOException("缺少资源链接产物 res.ap_：" + resApk);
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录 " + parent);
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("无法覆盖已存在的 APK " + output);
        }

        Set<String> written = new HashSet<>();
        int count = 0;
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(output)))) {

            // 1) res.ap_：res/** + AndroidManifest.xml + resources.arsc
            try (ZipFile source = new ZipFile(resApk)) {
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (!written.add(name)) {
                        info(log, "跳过重复条目 " + name);
                        continue;
                    }
                    boolean store = isStored(name);
                    try (InputStream input = source.getInputStream(entry)) {
                        copy(zip, name, input, store ? entry.getSize() : -1,
                                store ? entry.getCrc() : -1, store);
                    }
                    count++;
                }
            }

            // 2) dex
            if (dexFiles != null) {
                for (File dex : dexFiles) {
                    if (dex == null || !dex.isFile()) {
                        continue;
                    }
                    String name = dex.getName();
                    if (!written.add(name)) {
                        info(log, "跳过重复条目 " + name);
                        continue;
                    }
                    try (InputStream input = new java.io.FileInputStream(dex)) {
                        copy(zip, name, input, -1, -1, false);
                    }
                    count++;
                }
            }

            // 3) native libs → lib/<abi>/<name>（不压缩）
            if (nativeLibs != null) {
                for (Map.Entry<String, List<File>> abi : nativeLibs.entrySet()) {
                    List<File> libraries = abi.getValue();
                    if (libraries == null) {
                        continue;
                    }
                    for (File library : libraries) {
                        if (library == null || !library.isFile()) {
                            continue;
                        }
                        String name = "lib/" + abi.getKey() + "/" + library.getName();
                        if (!written.add(name)) {
                            info(log, "跳过重复条目 " + name);
                            continue;
                        }
                        try (InputStream input = new java.io.FileInputStream(library)) {
                            copy(zip, name, input, library.length(), crc32(library), true);
                        }
                        count++;
                    }
                }
            }

            // 4) java resources（src/main/resources）→ APK 根目录
            if (javaResDirs != null) {
                for (File directory : javaResDirs) {
                    count += addTree(zip, directory, directory, written, log);
                }
            }
        }

        info(log, "打包 " + count + " 个条目 → " + output.getName()
                + "（" + output.length() + " bytes）");
        return output.length();
    }

    /** 递归把目录内容写到 APK（保持相对路径）。 */
    private static int addTree(ZipOutputStream zip, File root, File directory,
                               Set<String> written, Log log) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            return 0;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return 0;
        }
        java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        int count = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                count += addTree(zip, root, child, written, log);
                continue;
            }
            String relative = root.toURI().relativize(child.toURI()).getPath();
            if (relative.isEmpty()) {
                relative = child.getName();
            }
            if (!written.add(relative)) {
                info(log, "跳过重复条目 " + relative);
                continue;
            }
            try (InputStream input = new java.io.FileInputStream(child)) {
                copy(zip, relative, input, -1, -1, false);
            }
            count++;
        }
        return count;
    }

    private static boolean isStored(String name) {
        if (STORED_NAMES.contains(name)) {
            return true;
        }
        for (String prefix : STORED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写一个条目。
     *
     * @param size 不压缩时必须提供（字节数）
     * @param crc  不压缩时必须提供（CRC-32）
     */
    private static void copy(ZipOutputStream zip, String name, InputStream input,
                             long size, long crc, boolean store) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (store) {
            if (size < 0 || crc < 0) {
                throw new IOException("不压缩条目缺少大小/CRC：" + name);
            }
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(size);
            entry.setCompressedSize(size);
            entry.setCrc(crc);
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }
        zip.putNextEntry(entry);
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) > 0) {
            zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private static long crc32(File file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    private static void info(Log log, String message) {
        if (log != null && message != null) {
            log.line(message);
        }
    }

    /** 按 {@code classes.dex → classes2.dex → …} 排序（APK 里必须这个顺序）。 */
    public static List<File> sortDexFiles(List<File> files) {
        List<File> sorted = new java.util.ArrayList<>(files);
        sorted.sort((left, right) -> dexIndex(left) - dexIndex(right));
        return sorted;
    }

    private static int dexIndex(File file) {
        String name = file.getName();
        if (name.startsWith("classes") && name.endsWith(".dex")) {
            String number = name.substring("classes".length(), name.length() - ".dex".length());
            if (number.isEmpty()) {
                return 1;
            }
            try {
                return Integer.parseInt(number);
            } catch (NumberFormatException ignored) {
                // 落到下面的兜底
            }
        }
        return Integer.MAX_VALUE;
    }

    /** 供日志展示的 abi → 文件数摘要。 */
    public static String describeNativeLibs(Map<String, List<File>> nativeLibs) {
        if (nativeLibs == null || nativeLibs.isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, List<File>> entry : nativeLibs.entrySet()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(entry.getKey()).append('(')
                    .append(entry.getValue() == null ? 0 : entry.getValue().size()).append(')');
        }
        return builder.toString();
    }

    /** 空 map 的便捷方法。 */
    public static Map<String, List<File>> emptyNativeLibs() {
        return new LinkedHashMap<>();
    }

    /** 判断是否是不压缩条目（测试用）。 */
    public static boolean isStoredEntry(String name) {
        return isStored(name == null ? "" : name.toLowerCase(Locale.ROOT));
    }

    /** 保留：把 APK 复制到别处（构建产物输出）。 */
    public static void copyTo(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        try (InputStream input = new java.io.FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
    }
}
