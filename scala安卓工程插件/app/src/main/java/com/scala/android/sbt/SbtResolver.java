package com.scala.android.sbt;

/**
 * sbt 里声明的一个仓库（{@code resolvers += "名字" at "地址"}）。
 */
public final class SbtResolver {

    public enum Kind {
        /** Maven 仓库（默认）。 */
        MAVEN,
        /** Ivy 布局。 */
        IVY,
        /** 本地目录（file:// 或普通路径）。 */
        LOCAL
    }

    public String name;
    public String url;
    public Kind kind = Kind.MAVEN;

    public SbtResolver() {
    }

    public SbtResolver(String name, String url) {
        this.name = name;
        this.url = url;
        this.kind = guessKind(url);
    }

    /** 认几个 sbt 常见简写：{} 里是 sbt 的等价地址。 */
    public static SbtResolver mavenCentral() {
        return new SbtResolver("Maven Central", "https://repo1.maven.org/maven2/");
    }

    public static SbtResolver aliyunPublic() {
        return new SbtResolver("Aliyun Public", "https://maven.aliyun.com/repository/public/");
    }

    public static SbtResolver aliyunGoogle() {
        return new SbtResolver("Aliyun Google", "https://maven.aliyun.com/repository/google/");
    }

    public static SbtResolver jitPack() {
        return new SbtResolver("JitPack", "https://jitpack.io/");
    }

    /** sbt 里的 {@code Resolver.mavenCentral} / {@code Resolver.jcenter} 等。 */
    public static SbtResolver byId(String id) {
        if (id == null) {
            return null;
        }
        switch (id) {
            case "mavenCentral":
                return mavenCentral();
            case "jcenter":
                return new SbtResolver("JCenter", "https://jcenter.bintray.com/");
            case "sonatypeRepo":
                return new SbtResolver("Sonatype", "https://oss.sonatype.org/content/repositories/releases/");
            case "google":
                return new SbtResolver("Google", "https://maven.google.com/");
            default:
                return null;
        }
    }

    private static Kind guessKind(String url) {
        if (url == null) {
            return Kind.MAVEN;
        }
        if (url.startsWith("file:") || url.startsWith("/")) {
            return Kind.LOCAL;
        }
        if (url.contains("ivy")) {
            return Kind.IVY;
        }
        return Kind.MAVEN;
    }

    public boolean isValid() {
        return url != null && !url.isEmpty();
    }

    @Override
    public String toString() {
        return (name == null ? url : name + " -> " + url);
    }
}
