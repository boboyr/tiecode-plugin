package com.tiecode.aarplugin.util;

import com.rapid.util.file.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件相关的小工具。
 */
public final class AarFiles {

    private AarFiles() {
    }

    public static File toFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return new File(path);
    }

    /** 递归收集目录下所有文件。 */
    public static List<File> listAllFiles(File dir) {
        List<File> result = new ArrayList<>();
        collect(dir, result);
        return result;
    }

    public static String sanitizeFileName(String name) {
        if (name == null) {
            return "unnamed";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public static boolean isNotEmptyDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File[] children = dir.listFiles();
        return children != null && children.length > 0;
    }

    public static void copyDirIfExists(File source, File target) throws java.io.IOException {
        if (source != null && source.isDirectory() && isNotEmptyDir(source)) {
            FileUtils.copyDir(source, target);
        }
    }

    public static void copyFileIfExists(File source, File target) throws java.io.IOException {
        if (source != null && source.isFile()) {
            FileUtils.ensureParentDir(target);
            FileUtils.copyFile(source, target);
        }
    }

    private static void collect(File file, List<File> out) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collect(child, out);
                }
            }
        } else if (file.isFile()) {
            out.add(file);
        }
    }
}
