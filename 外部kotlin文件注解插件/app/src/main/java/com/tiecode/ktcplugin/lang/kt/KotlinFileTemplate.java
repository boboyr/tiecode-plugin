package com.tiecode.ktcplugin.lang.kt;

import java.io.File;

/**
 * 「新建 Kotlin 类」的<b>纯逻辑</b>：文件名处理、包名推导、模板生成。
 *
 * <p>刻意与 {@link KotlinClassCreationOption} 分开：那边要继承宿主的
 * {@code BaseObject}、走表单与文件树回调，桌面环境无法实例化；
 * 这边只吃字符串和 {@link File}，可以直接单测。
 */
public final class KotlinFileTemplate {

    /** 结绳工程源码目录名（与宿主 {@code TiecodeProject.SOURCE_DIR_NAME} 一致）。 */
    public static final String SOURCE_DIR_NAME = "源代码";

    public static final String KOTLIN_SUFFIX = ".kt";

    private KotlinFileTemplate() {
    }

    /**
     * 从用户输入里取出类名：去掉首尾空白，并剥掉可能顺手带上的 {@code .kt}。
     *
     * @return 合法类名；输入为空或只剩后缀时返回 {@code null}
     */
    public static String className(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(KOTLIN_SUFFIX)) {
            // 哪怕用户只敲了 ".kt"，剥完也是空名字，下面统一返回 null。
            name = name.substring(0, name.length() - KOTLIN_SUFFIX.length()).trim();
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * 目录对应的包名。
     *
     * @param namespace    工程自身的包名（结绳工程 {@code project.json} 的 pkgName）；
     *                     非空时直接采用——工程内任意目录新建都用同一个包，
     *                     与结绳源文件的写法一致
     * @param projectDir   工程根目录；为 {@code null} 时跳过目录推导
     * @param directory    目标目录
     * @return 包名（可能为空串，表示默认包）
     */
    public static String packageName(String namespace, File projectDir, File directory) {
        return packageName(namespace, projectDir, directory, SOURCE_DIR_NAME);
    }

    /**
     * 同上，但显式指定源码根目录名。
     *
     * <p>单独给一个重载是为了可测试：源码根叫「源代码」是结绳的约定，
     * 而部分运行环境（如桌面 JVM 的 locale 不匹配时）对非 ASCII 路径处理不稳，
     * 测试里可以传 ASCII 目录名来验证推导逻辑本身。
     */
    public static String packageName(String namespace, File projectDir, File directory,
                                     String sourceDirName) {
        if (namespace != null && !namespace.trim().isEmpty()) {
            // 结绳工程的包名是工程级的：工程内任意目录新建都用同一个包，
            // 与结绳源文件的写法一致，也就不需要再按目录拼。
            return namespace.trim();
        }
        if (projectDir == null || directory == null) {
            return "";
        }
        try {
            // 源码根优先；没有源码目录的旧工程（或直接以工程根为源码根）就退化为工程根。
            File sourceRoot = sourceDirName == null || sourceDirName.isEmpty()
                    ? projectDir : new File(projectDir, sourceDirName);
            String root = (sourceRoot.isDirectory() ? sourceRoot : projectDir).getAbsolutePath();
            String path = directory.getAbsolutePath();
            if (!path.startsWith(root)) {
                return "";
            }
            String relative = path.substring(root.length());
            StringBuilder builder = new StringBuilder();
            for (String segment : relative.split("[/\\\\]")) {
                String value = segment.trim();
                if (value.isEmpty() || ".".equals(value) || !isIdentifier(value)) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('.');
                }
                builder.append(value);
            }
            return builder.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 生成 {@code .kt} 骨架。
     *
     * <p>末尾那段注释是刻意的：本插件的编译链路是「结绳源文件里写
     * {@code @外部kotlin文件} 注解 → 注解处理器把文件拷进编译源集」，
     * 单独放一个 .kt 是不会被编译的，与其让人以为它能自动生效，不如直接写在文件里。
     */
    public static String classTemplate(String packageName, String className) {
        StringBuilder builder = new StringBuilder();
        if (packageName != null && !packageName.isEmpty()) {
            builder.append("package ").append(packageName).append("\n\n");
        }
        builder.append("class ").append(className == null ? "" : className).append(" {\n")
                .append("\n")
                .append("}\n")
                .append("\n")
                .append("// 本文件要参与编译，请在结绳源文件里用 @外部kotlin文件(\"")
                .append(className == null ? "" : className).append(KOTLIN_SUFFIX)
                .append("\") 引用它。\n");
        return builder.toString();
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
