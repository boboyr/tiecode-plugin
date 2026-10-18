package com.dsh.libgdxeditor;

import java.io.File;

/**
 * 找当前工程放资源的地方。
 *
 * <p>结绳工程里资源根目录的命名不统一:新工程常见 {@code assets/},
 * 也有的工程叫 {@code 资源/}(例如「我的游戏」)。两个都要认,否则「贴图」
 * 属性选择器会拿不到资源根目录,只能退回系统文件选择器(起始在公共根目录)。</p>
 */
public final class ProjectAssets {

    /** 资源根目录的候选名(按优先级)。 */
    private static final String[] DIR_NAMES = {"assets", "资源", "Assets"};

    private ProjectAssets() {
    }

    /** 结绳文件的类名:文件名叫什么,生成的类就叫什么(小鸡顶篮球场景.t → 小鸡顶篮球场景)。 */
    public static String classNameFor(String filePath) {
        if (filePath == null) {
            return null;
        }
        String name = new File(filePath).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    /** 在 base 目录下按候选名找一个存在的资源根目录;找不到返回 null。 */
    private static File findIn(File base) {
        if (base == null || !base.isDirectory()) {
            return null;
        }
        for (String name : DIR_NAMES) {
            File dir = new File(base, name);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    public static String dir(String filePath) {
        try {
            com.rapid.api.project.Project project = com.rapid.api.Platform.getProject();
            if (project != null && project.getProjectDir() != null) {
                File found = findIn(project.getProjectDir());
                if (found != null) {
                    return found.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        if (filePath != null) {
            File dir = new File(filePath).getParentFile();
            for (int i = 0; dir != null && i < 6; i++, dir = dir.getParentFile()) {
                File found = findIn(dir);
                if (found != null) {
                    return found.getAbsolutePath();
                }
            }
        }
        return null;
    }
}
