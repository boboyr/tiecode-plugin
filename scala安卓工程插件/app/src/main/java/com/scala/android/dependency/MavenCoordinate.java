package com.scala.android.dependency;

import java.io.File;
import java.util.Locale;

/**
 * Maven 坐标（{@code groupId:artifactId:version[:classifier][@packaging]}）。
 *
 * <p>纯 Java 实现，不依赖 aether，也不依赖宿主 Java 插件 —— 这样 sbt 依赖解析
 * 在只有本插件的情况下也能工作，并且可以在开发机上单独跑测试。
 */
public final class MavenCoordinate {

    public final String groupId;
    public final String artifactId;
    public final String version;
    public final String classifier;
    /** jar / aar / pom …，默认 jar。 */
    public String packaging = "jar";

    public MavenCoordinate(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, null);
    }

    public MavenCoordinate(String groupId, String artifactId, String version, String classifier) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.classifier = classifier;
    }

    /** 解析 {@code g:a:v} 或 {@code g:a:classifier:v}。 */
    public static MavenCoordinate parse(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(":");
        if (parts.length < 3) {
            return null;
        }
        if (parts.length >= 4 && parts[2] != null && !parts[2].isEmpty()) {
            return new MavenCoordinate(parts[0], parts[1], parts[3], parts[2]);
        }
        return new MavenCoordinate(parts[0], parts[1], parts[2]);
    }

    /** 不含版本的稳定标识，用于冲突仲裁。 */
    public String key() {
        return groupId + ":" + artifactId
                + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
    }

    /** 本地仓库里的相对路径：{@code org/foo/bar/1.0/bar-1.0.jar}。 */
    public String relativePath(String extension) {
        StringBuilder builder = new StringBuilder();
        builder.append(groupId.replace('.', '/')).append('/');
        builder.append(artifactId).append('/');
        builder.append(version).append('/');
        builder.append(fileName(extension));
        return builder.toString();
    }

    public String fileName(String extension) {
        StringBuilder builder = new StringBuilder();
        builder.append(artifactId).append('-').append(version);
        if (classifier != null && !classifier.isEmpty()) {
            builder.append('-').append(classifier);
        }
        return builder.append('.').append(extension).toString();
    }

    /** 该坐标的 POM 的本地缓存文件。 */
    public File pomFile(File repositoryRoot) {
        return new File(repositoryRoot, relativePath("pom"));
    }

    /** 该坐标的构件的本地缓存文件。 */
    public File artifactFile(File repositoryRoot) {
        return new File(repositoryRoot, relativePath(packaging));
    }

    public boolean isSnapshot() {
        return version != null && version.toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT");
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version
                + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MavenCoordinate)) {
            return false;
        }
        MavenCoordinate that = (MavenCoordinate) other;
        return key().equals(that.key()) && String.valueOf(version).equals(String.valueOf(that.version));
    }

    @Override
    public int hashCode() {
        return (key() + ":" + version).hashCode();
    }
}
