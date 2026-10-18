package com.scala.android.project;

import com.rapid.api.Platform;
import com.rapid.api.project.Project;

import java.io.File;

/**
 * 包名 ↔ 源码目录 的换算，以及包名清洗。
 *
 * <p>两套工程布局都支持：
 * <ul>
 *   <li>控制台布局：源码根是 {@code <project>/src}；</li>
 *   <li>安卓布局（本插件）：源码根是 {@code <project>/src/main/scala}（依次回退
 *       {@code src/main/java}、{@code src}）。</li>
 * </ul>
 */
public final class ScalaSources {

    /** 控制台工程的源码根。 */
    public static final String SOURCE_ROOT = "src";
    /** 安卓工程（sbt 布局）的源码根。 */
    public static final String ANDROID_SOURCE_ROOT = "src/main/scala";
    /** 安卓工程的 Java 源码根。 */
    public static final String ANDROID_JAVA_ROOT = "src/main/java";

    private ScalaSources() {
    }

    /** 包名 → 相对路径（{@code com.user.console} + {@code Main.scala} → {@code src/com/user/console/Main.scala}）。 */
    public static String sourcePath(String packageName, String fileName) {
        return sourcePathUnder(SOURCE_ROOT, packageName, fileName);
    }

    /** 包名 → 相对路径（可指定源码根，如 {@link #ANDROID_SOURCE_ROOT}）。 */
    public static String sourcePathUnder(String sourceRoot, String packageName, String fileName) {
        String root = sourceRoot == null || sourceRoot.isEmpty() ? SOURCE_ROOT : sourceRoot;
        if (packageName == null || packageName.isEmpty()) {
            return root + File.separator + fileName;
        }
        return root + File.separator + packageName.replace('.', File.separatorChar)
                + File.separator + fileName;
    }

    /** 目录 → 包名（依次按安卓布局与控制台布局的源码根推导）。 */
    public static String packageOf(Project project, File directory) {
        if (project == null || directory == null) {
            return "";
        }
        for (String root : new String[]{ANDROID_SOURCE_ROOT, ANDROID_JAVA_ROOT, SOURCE_ROOT}) {
            String result = packageOf(project, directory, root);
            if (!result.isEmpty()) {
                return result;
            }
        }
        return "";
    }

    private static String packageOf(Project project, File directory, String sourceRoot) {
        try {
            String root = project.getChildFile(sourceRoot).getAbsolutePath();
            String path = directory.getAbsolutePath();
            if (!path.startsWith(root)) {
                return "";
            }
            path = path.substring(root.length());
            StringBuilder builder = new StringBuilder();
            for (String segment : path.split("[/\\\\]")) {
                String value = segment.trim();
                if (value.isEmpty() || ".".equals(value)) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('.');
                }
                // 目录名理论上都是合法包段，这里再兜一次底。
                builder.append(sanitizeSegment(value));
            }
            String result = builder.toString();
            return isUsablePackage(result) ? result : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 去掉非法字符，压缩多余的点，并保证每段以字母/下划线开头。 */
    public static String sanitizePackage(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                builder.append(c);
            } else if (c == '.' || c == '/' || c == '\\') {
                builder.append('.');
            }
        }
        StringBuilder result = new StringBuilder();
        for (String segment : builder.toString().split("\\.")) {
            String cleaned = sanitizeSegment(segment);
            if (cleaned.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('.');
            }
            result.append(cleaned);
        }
        return result.toString();
    }

    private static String sanitizeSegment(String segment) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (i == 0 && !Character.isJavaIdentifierStart(c)) {
                continue;
            }
            if (Character.isJavaIdentifierPart(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static boolean isUsablePackage(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (String segment : value.split("\\.")) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    /** 当前打开工程；拿不到就返回 null。 */
    public static Project currentProject() {
        try {
            return Platform.getProject();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
