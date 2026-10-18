package com.scala.android.dependency;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sbt 依赖解析的结果。
 *
 * <p>分成几类，是为了让下游任务各取所需：
 * <ul>
 *   <li>{@link #jars} → scalac / D8 的 classpath；</li>
 *   <li>{@link #aarClassesJars} → AAR 里的 classes.jar，同样进 classpath；</li>
 *   <li>{@link #aarResDirs} / {@link #aarManifests} → 资源合并与清单合并；</li>
 *   <li>{@link #aarJniLibs} → 打进 APK 的 {@code lib/<abi>/}；</li>
 *   <li>{@link #aarAssetsDirs} → 打进 APK 的 assets；</li>
 *   <li>{@link #unmanagedJars} → 工程 {@code lib/} 目录下的本地 jar。</li>
 * </ul>
 */
public final class SbtResolutionResult {

    public final List<File> jars = new ArrayList<>();
    /** 只参与编译、不进 APK 的 jar（sbt 里 {@code % Provided}、POM 里的 provided）。 */
    public final List<File> providedJars = new ArrayList<>();
    /** 只参与测试编译的 jar（{@code % Test}）。 */
    public final List<File> testJars = new ArrayList<>();
    public final List<File> aarClassesJars = new ArrayList<>();
    public final List<File> aarResDirs = new ArrayList<>();
    public final List<File> aarManifests = new ArrayList<>();
    public final List<File> aarAssetsDirs = new ArrayList<>();
    /** abi → .so 文件列表 */
    public final Map<String, List<File>> aarJniLibs = new LinkedHashMap<>();
    public final List<File> unmanagedJars = new ArrayList<>();
    /**
     * 被「本地 jar 兜底」认领过的 lib/ 文件（按绝对路径记）。
     * {@code collectUnmanagedJars} 会把它们从 unmanagedJars 里扣掉——
     * 这些 jar 已经按依赖的 scope 归进 provided/test/compile 了，
     * 再进 unmanagedJars（默认进 dex 的那份）会让 provided 的 jar 混进打包 classpath。
     */
    public final java.util.Set<String> claimedLocalJars = new java.util.LinkedHashSet<>();

    /** 解析成功的坐标（含传递依赖）。 */
    public final List<String> resolvedCoordinates = new ArrayList<>();
    /** 解析失败的坐标及原因。 */
    public final List<String> failures = new ArrayList<>();
    /** 解析过程中的日志行。 */
    public final List<String> logLines = new ArrayList<>();

    /** 编译用的完整 classpath（含 provided / test）。 */
    public List<File> classpath() {
        List<File> result = new ArrayList<>();
        result.addAll(jars);
        result.addAll(providedJars);
        result.addAll(testJars);
        result.addAll(aarClassesJars);
        result.addAll(unmanagedJars);
        return result;
    }

    /** 打进 APK / 参与 dex 的 classpath（不含 provided / test）。 */
    public List<File> runtimeClasspath() {
        List<File> result = new ArrayList<>();
        result.addAll(jars);
        result.addAll(aarClassesJars);
        result.addAll(unmanagedJars);
        return result;
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public String summary() {
        return "依赖解析：成功 " + resolvedCoordinates.size() + " 个坐标 · classpath "
                + classpath().size() + " 个条目 · AAR " + aarClassesJars.size()
                + " 个 · 本地 jar " + unmanagedJars.size()
                + (providedJars.isEmpty() ? "" : " · provided " + providedJars.size())
                + (failures.isEmpty() ? "" : " · 失败 " + failures.size() + " 个");
    }
}
