package com.scala.android.sbt;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条 sbt 依赖声明（{@code libraryDependencies} 的元素）。
 *
 * <pre>
 * "org.scala-lang" %  "scala3-library_3" % "3.8.1"      → crossVersion = false
 * "com.squareup.okhttp3" %% "okhttp"      % "4.12.0"     → crossVersion = true（补 _3 / _2.13）
 * "junit" % "junit" % "4.13.2" % Test                     → configuration = "test"
 * </pre>
 */
public final class SbtModuleId {

    public String organization;
    public String name;
    public String revision;
    /** true 表示用 {@code %%}：坐标名要补 Scala 二进制版本后缀。 */
    public boolean crossVersion;
    /** 配置（compile/runtime/test/provided…），可为 null。 */
    public String configuration;
    /** classifier（sources/javadoc…）。 */
    public String classifier;
    /** 是否 {@code test} 作用域。 */
    public boolean test;
    /** 是否只参与编译（provided）。 */
    public boolean provided;
    /** 本地文件路径（依赖写成 {@code "…%…%…%… from "file:///…"} 或
     * {@code "…%…%…%…%… @ file("/…")} 等带文件路径的形式时填这里；
     * 命中时解析器直接读本地文件，不走 Maven 仓库，也不下载。 */
    public String filePath;
    /** {@code .exclude(...)} 收集到的排除项，形如 {@code org:name}。 */
    public final List<String> excludes = new ArrayList<>();
    /** 原始写法，用于日志。 */
    public String raw;

    public SbtModuleId() {
    }

    public SbtModuleId(String organization, String name, String revision) {
        this.organization = organization;
        this.name = name;
        this.revision = revision;
    }

    /** 是否是一条完整可解析的坐标。 */
    public boolean isResolvable() {
        return organization != null && !organization.isEmpty()
                && name != null && !name.isEmpty()
                && revision != null && !revision.isEmpty();
    }

    /**
     * 转成 Maven 坐标。
     *
     * @param scalaBinaryVersion 交叉版本后缀，如 {@code "3"}（Scala 3）或 {@code "2.13"}
     */
    public String toMavenCoordinate(String scalaBinaryVersion) {
        return toMavenCoordinate(scalaBinaryVersion, false, false);
    }

    /**
     * @param sources 是否要 sources 包
     * @param javadoc 是否要 javadoc 包
     */
    public String toMavenCoordinate(String scalaBinaryVersion, boolean sources, boolean javadoc) {
        StringBuilder builder = new StringBuilder();
        builder.append(organization).append(':');
        builder.append(mavenArtifactId(scalaBinaryVersion)).append(':');
        if (classifier != null && !classifier.isEmpty()) {
            builder.append(classifier).append(':');
        } else if (sources) {
            builder.append("sources:");
        } else if (javadoc) {
            builder.append("javadoc:");
        }
        builder.append(revision);
        return builder.toString();
    }

    /** {@code %%} 时补 {@code _3} / {@code _2.13} 后缀。 */
    public String mavenArtifactId(String scalaBinaryVersion) {
        if (!crossVersion || scalaBinaryVersion == null || scalaBinaryVersion.isEmpty()) {
            return name;
        }
        return name + "_" + scalaBinaryVersion;
    }

    /** 该依赖会被放到哪个 classpath。 */
    public boolean participatesInCompile() {
        return !test && !provided;
    }

    public boolean participatesInRuntime() {
        return !provided;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(organization).append(crossVersion ? " %%" : " %")
                .append(' ').append(name).append(" % ").append(revision);
        if (configuration != null) {
            builder.append(" % ").append(configuration);
        }
        return builder.toString();
    }
}
