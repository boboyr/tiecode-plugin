package com.scala.android.task;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 工程内的文件收集/排序小工具。
 */
public final class ScalaFiles {

    private ScalaFiles() {
    }

    /** 递归收集后缀匹配的文件（按路径排序，保证构建结果稳定）。 */
    public static List<File> collect(File directory, String suffix) {
        List<File> result = new ArrayList<>();
        collectInto(directory, suffix.toLowerCase(Locale.ROOT), result);
        Collections.sort(result, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        });
        return result;
    }

    private static void collectInto(File directory, String suffix, List<File> result) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectInto(child, suffix, result);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                result.add(child);
            }
        }
    }

    /**
     * 按 {@code classes.dex → classes2.dex → ... → classes10.dex} 的自然顺序排序，
     * dex 文件名必须从 classes.dex 开始连续编号，ART 才会把 jar 里的多 dex 全部加载。
     */
    public static List<File> sortDexFiles(List<File> files) {
        List<File> sorted = new ArrayList<>(files);
        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return dexIndex(left) - dexIndex(right);
            }
        });
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
}
