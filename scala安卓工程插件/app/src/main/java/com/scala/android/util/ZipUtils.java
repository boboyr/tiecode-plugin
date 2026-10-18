package com.scala.android.util;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 资源读取 + 纯 Java 解包（工程模板用）。
 *
 * <h3>为什么不直接遍历 assets 目录</h3>
 * 插件运行时是用 {@code AssetManager.addAssetPath(<插件.rmx>)} 建的资源管理器，
 * {@code AssetManager.list()} 对"手工打的 zip"里的<b>嵌套目录</b>并不可靠：
 * 实测 {@code assets/templates/.../project/} 整棵子树在设备上被静默漏掉，
 * 而同一个 assets 里 {@code scala-toolchain/lib/*.jar} 用 {@code open()} 读取是正常的。
 *
 * <p>所以模板不再靠 {@code list()} 递归，而是：
 * <b>打包成一个 zip 资源 → {@code open()} 读进来 → 用 java.util.zip 解包</b>。
 * 只依赖"能按路径读单个文件"这一条（已经被标准库 jar 的安装流程验证过）。
 */
public final class ZipUtils {

    private ZipUtils() {
    }

    /** assets 里是否存在该文件（能 open 出来即存在）。 */
    public static boolean assetExists(Context context, String assetPath) {
        try (InputStream input = context.getAssets().open(assetPath)) {
            return input != null;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 把 assets 里的文件原样复制到目标文件。 */
    public static boolean copyAsset(Context context, String assetPath, File target)
            throws IOException {
        AssetManager assets = context.getAssets();
        try (InputStream input = assets.open(assetPath)) {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建目录 " + parent);
            }
            try (OutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
            return true;
        }
    }

    /**
     * 解包 zip 到目标目录。
     *
     * @return 解出来的文件（相对 targetDir 的路径）
     */
    public static List<String> unzip(File zipFile, File targetDir) throws IOException {
        List<String> written = new ArrayList<>();
        if (!targetDir.isDirectory() && !targetDir.mkdirs() && !targetDir.isDirectory()) {
            throw new IOException("无法创建目录 " + targetDir);
        }
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/")) {
                    continue;
                }
                File target = new File(targetDir, name);
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                        throw new IOException("无法创建目录 " + target);
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录 " + parent);
                }
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = new FileOutputStream(target)) {
                    copy(input, output);
                }
                written.add(name);
            }
        }
        return written;
    }

    /**
     * 对目录里的文本文件做占位符替换（解包之后调用）。
     *
     * @return 实际做过替换的文件数
     */
    public static int replacePlaceholders(File directory, Map<String, String> replacements) {
        if (directory == null || !directory.isDirectory() || replacements == null
                || replacements.isEmpty()) {
            return 0;
        }
        int[] count = new int[1];
        replaceInDirectory(directory, replacements, count);
        return count[0];
    }

    private static void replaceInDirectory(File directory, Map<String, String> replacements,
                                           int[] count) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                replaceInDirectory(child, replacements, count);
                continue;
            }
            if (!AssetTree.isText(child.getName())) {
                continue;
            }
            try {
                String text = readText(child);
                if (text == null || text.indexOf("${") < 0) {
                    continue;
                }
                String replaced = text;
                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                    replaced = replaced.replace("${" + entry.getKey() + "}",
                            entry.getValue() == null ? "" : entry.getValue());
                }
                if (!replaced.equals(text)) {
                    writeText(child, replaced);
                    count[0]++;
                }
            } catch (Throwable ignored) {
                // 单个文件替换失败不影响整体
            }
        }
    }

    public static String readText(File file) {
        try (InputStream input = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copy(input, output);
            return new String(output.toByteArray(), "UTF-8");
        } catch (Throwable error) {
            return null;
        }
    }

    public static void writeText(File file, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes("UTF-8"));
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
    }
}
