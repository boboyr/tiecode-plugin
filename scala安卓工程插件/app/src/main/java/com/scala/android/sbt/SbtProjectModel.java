package com.scala.android.sbt;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sbt 构建里的一个（子）工程：{@code lazy val root = (project in file(".")).settings(...)}。
 *
 * <p>setting 用「规范化键路径 → 值」保存，键路径的写法与源码一致
 * （{@code Compile / scalacOptions} 存成 {@code Compile/scalacOptions}），
 * 取值时由 {@link SbtBuild} 负责按 scope 回退。
 */
public final class SbtProjectModel {

    /** 定义它的 val 名，如 {@code root}。 */
    public String id;
    /** 工程目录（已相对构建根解析成绝对路径）。 */
    public File baseDir;
    /** {@code .enablePlugins(...)} 里列的插件类名（简名）。 */
    public final List<String> enabledPlugins = new ArrayList<>();
    /** 该工程自己的 setting。 */
    public final Map<String, SbtValue> settings = new LinkedHashMap<>();
    /** 该工程依赖的子工程 id（{@code .dependsOn(...)}）。 */
    public final List<String> dependsOn = new ArrayList<>();
    /** 该工程聚合的子工程 id（{@code .aggregate(...)}）。 */
    public final List<String> aggregate = new ArrayList<>();
    /** 定义位置（调试用）。 */
    public int line;

    public boolean isRoot(File buildRoot) {
        return baseDir != null && buildRoot != null
                && baseDir.getAbsolutePath().equals(buildRoot.getAbsolutePath());
    }

    public String getName() {
        SbtValue name = settings.get("name");
        String value = name == null ? null : name.asString();
        return value == null || value.isEmpty() ? id : value;
    }

    @Override
    public String toString() {
        return id + " @ " + baseDir;
    }
}
