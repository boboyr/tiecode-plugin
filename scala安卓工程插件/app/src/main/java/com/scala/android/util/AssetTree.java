package com.scala.android.util;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把插件 assets 里的目录整棵拷到设备目录，并对文本文件做占位符替换。
 *
 * <p>为什么不用 {@code AssetUtils.unZipAssetsFolder}：工程模板要保持"普通目录"的形态
 * （方便直接在仓库里看/改），所以这里递归 {@link AssetManager#list(String)} 自己拷，
 * 顺便区分文本（替换 {@code ${xxx}}）与二进制（原样拷，比如 png、keystore）。
 *
 * <p><b>为什么先 try open 再 list</b>：aapt2 有时不会为子目录留下目录项，
 * 此时 {@code list(子目录)} 返回空数组，如果只靠 {@code list().length} 判断
 * "是文件还是目录"，就会把目录当成文件去 open 而失败（表现为整个子目录丢失，
 * 例如模板里的 {@code project/}）。所以这里改成：
 * <ol>
 *   <li>能 {@code open()} 成功 → 是文件，直接拷；</li>
 *   <li>否则再试 {@code list()}，拿到内容 → 递归拷目录。</li>
 * </ol>
 */
public final class AssetTree {

    private AssetTree() {
    }

    /**
     * @param assetDir     assets 下的相对目录，如 {@code templates/project/scala_android_sbt}
     * @param targetDir    目标目录（不存在会创建）
     * @param replacements 占位符 → 值（只作用于文本文件）
     * @return 实际写出的文件（相对 targetDir 的路径，正斜杠分隔）
     */
    public static List<String> copy(Context context, String assetDir, File targetDir,
                                    Map<String, String> replacements) throws IOException {
        AssetManager assets = context.getAssets();
        if (!targetDir.isDirectory() && !targetDir.mkdirs() && !targetDir.isDirectory()) {
            throw new IOException("无法创建目录 " + targetDir);
        }
        List<String> written = new ArrayList<>();
        copyDirectory(assets, assetDir, targetDir, targetDir, replacements, written);
        return written;
    }

    private static void copyDirectory(AssetManager assets, String assetPath, File targetDir,
                                      File rootDir, Map<String, String> replacements,
                                      List<String> written) throws IOException {
        String[] children = listDirectory(assets, assetPath);
        for (String child : children) {
            String childAsset = assetPath + "/" + child;
            File childTarget = new File(targetDir, child);
            if (isFile(assets, childAsset)) {
                copyFile(assets, childAsset, childTarget, replacements);
            } else {
                if (!childTarget.isDirectory() && !childTarget.mkdirs() && !childTarget.isDirectory()) {
                    throw new IOException("无法创建目录 " + childTarget);
                }
                copyDirectory(assets, childAsset, childTarget, rootDir, replacements, written);
                continue;
            }
            written.add(relative(rootDir, childTarget));
        }
    }

    /** 目录项拿不到时退化成"空目录"，避免整体失败。 */
    private static String[] listDirectory(AssetManager assets, String assetPath) {
        try {
            String[] children = assets.list(assetPath);
            return children == null ? new String[0] : children;
        } catch (Throwable error) {
            return new String[0];
        }
    }

    /** 能 open 出来就是文件（目录会抛 FileNotFoundException），顺带把探测用的流关掉。 */
    private static boolean isFile(AssetManager assets, String assetPath) {
        try (InputStream input = assets.open(assetPath)) {
            return input != null;
        } catch (Throwable error) {
            return false;
        }
    }

    private static String relative(File root, File file) {
        try {
            String value = root.toURI().relativize(file.toURI()).getPath();
            return value;
        } catch (Throwable ignored) {
            return file.getName();
        }
    }

    private static void copyFile(AssetManager assets, String assetPath, File target,
                                 Map<String, String> replacements) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        byte[] data = readAll(assets, assetPath);
        if (data == null) {
            throw new IOException("读取 assets 失败：" + assetPath);
        }
        if (replacements != null && !replacements.isEmpty() && isText(target.getName())) {
            String text = new String(data, "UTF-8");
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                text = text.replace("${" + entry.getKey() + "}",
                        entry.getValue() == null ? "" : entry.getValue());
            }
            data = text.getBytes("UTF-8");
        }
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(data);
        }
    }

    private static byte[] readAll(AssetManager assets, String assetPath) {
        try (InputStream input = assets.open(assetPath)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (Throwable error) {
            return null;
        }
    }

    /** 需要做占位符替换的文本文件。 */
    public static boolean isText(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sbt") || lower.endsWith(".scala") || lower.endsWith(".sc")
                || lower.endsWith(".java") || lower.endsWith(".xml") || lower.endsWith(".properties")
                || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")
                || lower.endsWith(".pro") || lower.equals(".sbtopts") || lower.equals(".gitignore");
    }

    public static void writeText(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(text.getBytes("UTF-8"));
        }
    }
}
