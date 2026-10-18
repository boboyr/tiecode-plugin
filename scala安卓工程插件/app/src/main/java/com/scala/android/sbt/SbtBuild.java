package com.scala.android.sbt;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 sbt 构建脚本目录解析后的完整模型。
 *
 * <p>由 {@link SbtBuildScriptLoader} 产出：读 {@code build.sbt} / {@code *.sbt} /
 * {@code project/*.sbt} / {@code project/build.properties}，合并成"设置 + 子工程 +
 * sbt 插件 + 诊断"四块。编译打包任务只依赖这个模型，不直接碰脚本文本。
 */
public final class SbtBuild {

    /** 构建根目录（build.sbt 所在目录）。 */
    public File buildRoot;
    /** 参与解析的脚本文件（按解析顺序）。 */
    public final List<File> scriptFiles = new ArrayList<>();
    /** project/build.properties 里的 sbt.version。 */
    public String sbtVersion;
    /** 根作用域（隐式工程）的 setting。 */
    public final Map<String, SbtValue> settings = new LinkedHashMap<>();
    /** 显式声明的子工程。 */
    public final List<SbtProjectModel> projects = new ArrayList<>();
    /** project/plugins.sbt 里的 addSbtPlugin。 */
    public final List<SbtPlugin> plugins = new ArrayList<>();
    /** 解析过程中的警告/错误。 */
    public final List<SbtIssue> issues = new ArrayList<>();

    // ------------------------------------------------------------ 基本查询

    public String scalaVersion() {
        String value = getString(null, "scalaVersion");
        return value == null || value.isEmpty() ? null : value;
    }

    /** Scala 二进制版本：{@code 3.8.4 → 3}，{@code 2.13.12 → 2.13}。 */
    public String scalaBinaryVersion() {
        String version = scalaVersion();
        if (version == null) {
            return "3";
        }
        if (version.startsWith("3.")) {
            return "3";
        }
        if (version.startsWith("2.13")) {
            return "2.13";
        }
        if (version.startsWith("2.12")) {
            return "2.12";
        }
        int index = version.indexOf('.');
        if (index > 0) {
            int second = version.indexOf('.', index + 1);
            return second > 0 ? version.substring(0, second) : version;
        }
        return version;
    }

    /** 主工程：目录等于构建根的那个；没有就用唯一的一个；再没有就 null。 */
    public SbtProjectModel mainProject() {
        for (SbtProjectModel project : projects) {
            if (project.isRoot(buildRoot)) {
                return project;
            }
        }
        if (projects.size() == 1) {
            return projects.get(0);
        }
        return null;
    }

    public SbtProjectModel findProject(String id) {
        for (SbtProjectModel project : projects) {
            if (project != null && project.id != null && project.id.equals(id)) {
                return project;
            }
        }
        return null;
    }

    /**
     * 真正的"Android 应用工程"：多模块工程里 root 常常只是聚合壳，
     * 应用配置在 {@code app} 这类子工程上。
     *
     * <p>挑选顺序：启用了 AndroidApp/Android 插件的工程 → 声明了
     * {@code androidApplicationId} 的工程 → 目录等于构建根的工程 → 第一个工程。
     */
    public SbtProjectModel androidProject() {
        for (SbtProjectModel project : projects) {
            for (String plugin : project.enabledPlugins) {
                String lower = plugin.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("androidapp") || lower.contains("androidapplication")) {
                    return project;
                }
            }
        }
        for (SbtProjectModel project : projects) {
            if (project.settings.containsKey("androidApplicationId")) {
                return project;
            }
        }
        for (SbtProjectModel project : projects) {
            if (project.isRoot(buildRoot)) {
                return project;
            }
        }
        return projects.isEmpty() ? null : projects.get(0);
    }

    /** 是否有 Android 工程插件（决定是否读 platformTarget 之类的键）。 */
    public boolean hasAndroidPlugin() {
        for (SbtPlugin plugin : plugins) {
            if (plugin.isAndroidPlugin()) {
                return true;
            }
        }
        // 有些工程把插件声明放在别的 build 里，或用的是自己的包装插件：
        // 只要某个工程 enablePlugins 了名字里带 Android 的插件，就按安卓工程处理。
        for (SbtProjectModel project : projects) {
            for (String enabled : project.enabledPlugins) {
                if (enabled != null
                        && enabled.toLowerCase(java.util.Locale.ROOT).contains("android")) {
                    return true;
                }
            }
        }
        // 还有第三种证据：脚本里直接用了 android 系列的键（androidApplicationId、platformTarget…）。
        // 没有 project/plugins.sbt 的工程就是靠这一条认出来的。
        if (mentionsAndroidKeys(settings) || mentionsAndroidKeys(
                mainProject() == null ? null : mainProject().settings)) {
            return true;
        }
        // 第四种证据：显式的结绳插件开关（tiecodePlugin := true）——
        // 结绳插件工程没有 sbt 安卓插件、也常用不到 android 键，靠这一条认出来。
        return TiecodePluginKeys.isTiecodePluginProject(this, null);
    }

    private static boolean mentionsAndroidKeys(Map<String, SbtValue> settings) {
        if (settings == null) {
            return false;
        }
        for (String key : settings.keySet()) {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("android") || lower.equals("platformtarget")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 取值回退

    /**
     * 按 sbt 的作用域回退取 setting：
     * 工程自身 → 工程的 ThisBuild → 根作用域 → 根的 ThisBuild。
     */
    public SbtValue get(SbtProjectModel project, String key) {
        if (project != null) {
            SbtValue value = project.settings.get(key);
            if (value != null) {
                return value;
            }
            value = project.settings.get("ThisBuild/" + key);
            if (value != null) {
                return value;
            }
        }
        SbtValue value = settings.get(key);
        if (value != null) {
            return value;
        }
        return settings.get("ThisBuild/" + key);
    }

    public String getString(SbtProjectModel project, String key) {
        SbtValue value = get(project, key);
        return value == null ? null : value.asString();
    }

    public Integer getInt(SbtProjectModel project, String key) {
        SbtValue value = get(project, key);
        return value == null ? null : value.asInt();
    }

    public Boolean getBoolean(SbtProjectModel project, String key) {
        SbtValue value = get(project, key);
        return value == null ? null : value.asBoolean();
    }

    public File getFile(SbtProjectModel project, String key) {
        SbtValue value = get(project, key);
        return value == null ? null : value.asFile();
    }

    public List<SbtValue> getList(SbtProjectModel project, String key) {
        SbtValue value = get(project, key);
        return value == null ? new ArrayList<>() : value.asList();
    }

    public List<String> getStringList(SbtProjectModel project, String key) {
        List<String> result = new ArrayList<>();
        for (SbtValue value : getList(project, key)) {
            String text = value.asString();
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 依赖与仓库

    /** 工程（含根回退）声明的依赖。 */
    public List<SbtModuleId> dependencies(SbtProjectModel project) {
        List<SbtModuleId> result = new ArrayList<>();
        for (SbtValue value : getList(project, "libraryDependencies")) {
            collectModules(value, result);
        }
        for (SbtValue value : getList(project, "Compile/libraryDependencies")) {
            collectModules(value, result);
        }
        // 去重（同一坐标在 project 与 根作用域各存一份时会收两遍）
        return dedupe(result);
    }

    private static List<SbtModuleId> dedupe(List<SbtModuleId> modules) {
        List<SbtModuleId> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SbtModuleId module : modules) {
            if (seen.add(module == null ? null : module.toString())) {
                result.add(module);
            }
        }
        return result;
    }

    private static void collectModules(SbtValue value, List<SbtModuleId> result) {
        if (value == null) {
            return;
        }
        if (value.kind == SbtValue.Kind.MODULE) {
            result.add(value.module);
        } else if (value.kind == SbtValue.Kind.LIST) {
            for (SbtValue element : value.asList()) {
                collectModules(element, result);
            }
        }
    }

    /** 脚本里声明的仓库（不包含内置默认仓库）。 */
    public List<SbtResolver> resolvers(SbtProjectModel project) {
        List<SbtResolver> result = new ArrayList<>();
        for (SbtValue value : getList(project, "resolvers")) {
            if (value.kind == SbtValue.Kind.RESOLVER && value.resolver != null) {
                result.add(value.resolver);
            }
        }
        for (SbtValue value : getList(project, "externalResolvers")) {
            if (value.kind == SbtValue.Kind.RESOLVER && value.resolver != null) {
                result.add(value.resolver);
            }
        }
        return result;
    }

    /** 编译输出目录（相对构建根）。 */
    public List<String> scalacOptions(SbtProjectModel project) {
        List<String> options = getStringList(project, "Compile/scalacOptions");
        if (options.isEmpty()) {
            options = getStringList(project, "scalacOptions");
        }
        return options;
    }

    /** 供日志展示：每个诊断一行。 */
    public List<String> issueLines() {
        List<String> lines = new ArrayList<>();
        for (SbtIssue issue : issues) {
            lines.add(issue.format());
        }
        return lines;
    }
}
