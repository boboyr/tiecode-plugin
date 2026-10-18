package com.scala.android.sbt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * sbt 构建脚本装载器：把一个工程目录里的 .sbt 全部解析、求值、合并成 {@link SbtBuild}。
 *
 * <h3>读取顺序</h3>
 * <pre>
 * project/build.properties   → sbt.version
 * project/*.sbt              → 插件声明（plugins.sbt 优先）
 * project/*.scala            → 只登记不解析（记一条警告）
 * build.sbt                  → 主构建脚本
 * 其它 *.sbt                 → 按文件名排序
 * </pre>
 * 顺序很重要：plugins.sbt 里的 {@code addSbtPlugin} 先被记录，
 * 后面的 build.sbt 才知道"这是一个 Android 工程"。
 */
public final class SbtBuildScriptLoader {

    private SbtBuildScriptLoader() {
    }

    /** 解析工程目录；目录里没有 .sbt 时返回 null。 */
    public static SbtBuild load(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) {
            return null;
        }
        List<File> scripts = collectScripts(projectDir);
        if (scripts.isEmpty()) {
            return null;
        }
        SbtBuild build = new SbtBuild();
        build.buildRoot = projectDir.getAbsoluteFile();
        build.sbtVersion = readSbtVersion(new File(projectDir, "project/build.properties"));

        SbtEvaluator evaluator = new SbtEvaluator(build);
        for (File script : scripts) {
            build.scriptFiles.add(script);
            try {
                String source = readFile(script);
                SbtParser parser = SbtParser.of(script.getName(), source);
                List<SbtNode> statements = parser.parseFile();
                build.issues.addAll(parser.getIssues());
                evaluator.evaluate(script.getName(), statements);
            } catch (Throwable error) {
                build.issues.add(SbtIssue.error(script.getName(), 0, 0,
                        "读取/解析失败：" + error));
            }
        }
        warnAboutScalaBuildFiles(projectDir, build);
        if (build.projects.isEmpty()) {
            // 没有显式 project 定义：整个构建就是"根目录工程"。
            SbtProjectModel implicitProject = new SbtProjectModel();
            implicitProject.id = projectDir.getName();
            implicitProject.baseDir = build.buildRoot;
            build.projects.add(implicitProject);
        }
        return build;
    }

    /** 只解析文本（供单元测试 / 编辑器内预览用）。 */
    public static SbtBuild parseText(File buildRoot, String fileName, String source) {
        SbtBuild build = new SbtBuild();
        build.buildRoot = buildRoot == null ? new File(".").getAbsoluteFile() : buildRoot.getAbsoluteFile();
        SbtEvaluator evaluator = new SbtEvaluator(build);
        SbtParser parser = SbtParser.of(fileName, source);
        List<SbtNode> statements = parser.parseFile();
        build.issues.addAll(parser.getIssues());
        evaluator.evaluate(fileName, statements);
        if (build.projects.isEmpty()) {
            SbtProjectModel implicitProject = new SbtProjectModel();
            implicitProject.id = build.buildRoot.getName();
            implicitProject.baseDir = build.buildRoot;
            build.projects.add(implicitProject);
        }
        return build;
    }

    private static List<File> collectScripts(File projectDir) {
        List<File> scripts = new ArrayList<>();
        File projectSubDir = new File(projectDir, "project");
        if (projectSubDir.isDirectory()) {
            File[] children = projectSubDir.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                // plugins.sbt 必须最先
                for (File child : children) {
                    if (child.isFile() && child.getName().equals("plugins.sbt")) {
                        scripts.add(child);
                    }
                }
                for (File child : children) {
                    if (child.isFile() && child.getName().endsWith(".sbt")
                            && !child.getName().equals("plugins.sbt")) {
                        scripts.add(child);
                    }
                }
            }
        }
        File buildSbt = new File(projectDir, "build.sbt");
        if (buildSbt.isFile()) {
            scripts.add(buildSbt);
        }
        File[] rootChildren = projectDir.listFiles();
        if (rootChildren != null) {
            Arrays.sort(rootChildren, Comparator.comparing(File::getName));
            for (File child : rootChildren) {
                if (child.isFile() && child.getName().endsWith(".sbt") && !child.equals(buildSbt)) {
                    scripts.add(child);
                }
            }
        }
        return scripts;
    }

    private static void warnAboutScalaBuildFiles(File projectDir, SbtBuild build) {
        File projectSubDir = new File(projectDir, "project");
        File[] children = projectSubDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().endsWith(".scala")) {
                build.issues.add(SbtIssue.warning(child.getName(), 0, 0,
                        "project/ 下的 .scala 构建定义不参与解析（只支持 .sbt 语法子集）"));
            }
        }
    }

    /** project/build.properties 里的 {@code sbt.version=...}。 */
    public static String readSbtVersion(File propertiesFile) {
        if (propertiesFile == null || !propertiesFile.isFile()) {
            return null;
        }
        try {
            for (String line : readFile(propertiesFile).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    continue;
                }
                int index = trimmed.indexOf('=');
                if (index > 0 && "sbt.version".equals(trimmed.substring(0, index).trim())) {
                    return trimmed.substring(index + 1).trim();
                }
            }
        } catch (Throwable ignored) {
            // 版本读不出来不影响构建。
        }
        return null;
    }

    private static String readFile(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }
}
