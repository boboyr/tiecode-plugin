package com.scala.android.dependency;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 仓库客户端：POM 与构件（jar/aar）的本地缓存 + 远程下载。
 *
 * <h3>查找顺序</h3>
 * <ol>
 *   <li>本插件的可写缓存（{@code <env>/sbt-m2}，m2 布局）；</li>
 *   <li>只读的既有本地仓库（{@code ~/.m2/repository}、{@code ~/.ivy2/cache} …）；</li>
 *   <li>sbt 脚本里声明的仓库 + 内置镜像，按顺序下载并写入缓存。</li>
 * </ol>
 * 下载失败不抛异常，只记一条日志并返回 null —— 交给上层决定是
 * "跳过这条依赖继续编译" 还是 "报错停止"。
 */
public final class MavenRepositoryClient {

    /** 日志回调（避免依赖 Android Logger，方便 JVM 测试）。 */
    public interface Log {
        void line(String message);
    }

    private final File cacheDir;
    private final List<String> repositories = new ArrayList<>();
    private final List<File> readOnlyRoots = new ArrayList<>();
    private boolean offline;
    private Log log;
    private int connectTimeoutMs = 20000;
    private int readTimeoutMs = 60000;

    public MavenRepositoryClient(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
    }

    public void setLog(Log log) {
        this.log = log;
    }

    public void setTimeouts(int connectMs, int readMs) {
        this.connectTimeoutMs = connectMs;
        this.readTimeoutMs = readMs;
    }

    public File getCacheDir() {
        return cacheDir;
    }

    /** 添加远程仓库（{@code https://repo1.maven.org/maven2/} 这种形式）。 */
    public void addRepository(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        String normalized = url.trim();
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        if (!repositories.contains(normalized)) {
            repositories.add(normalized);
        }
    }

    public List<String> getRepositories() {
        return repositories;
    }

    /** 添加只读的既有本地仓库（默认加上 m2 与 ivy2 布局）。 */
    public void addReadOnlyRoot(File root) {
        if (root != null && root.isDirectory() && !readOnlyRoots.contains(root)) {
            readOnlyRoots.add(root);
        }
    }

    /** 按平台惯例补上常见本地仓库。 */
    public void addDefaultLocalRoots() {
        String home = System.getProperty("user.home");
        if (home == null) {
            return;
        }
        addReadOnlyRoot(new File(home, ".m2/repository"));
        addReadOnlyRoot(new File(home, ".m2/repository/.cache"));
    }

    // ------------------------------------------------------------ 取文件

    /** 取 POM；本地有就直接返回，否则下载。 */
    public File fetchPom(MavenCoordinate coordinate) {
        return fetch(coordinate, "pom", false);
    }

    /** 取构件（jar/aar/…）。 */
    public File fetchArtifact(MavenCoordinate coordinate) {
        String extension = coordinate.packaging == null || coordinate.packaging.isEmpty()
                ? "jar" : coordinate.packaging;
        return fetch(coordinate, extension, true);
    }

    private File fetch(MavenCoordinate coordinate, String extension, boolean allowIvyLayout) {
        // 1. 可写缓存
        File cached = new File(cacheDir, coordinate.relativePath(extension));
        if (cached.isFile() && cached.length() > 0) {
            return cached;
        }
        // 2. 既有本地仓库
        for (File root : readOnlyRoots) {
            File local = new File(root, coordinate.relativePath(extension));
            if (local.isFile() && local.length() > 0) {
                info("使用本地缓存 " + local);
                return local;
            }
        }
        if (allowIvyLayout) {
            File ivy = findInIvyCache(coordinate, extension);
            if (ivy != null) {
                info("使用 Ivy 缓存 " + ivy);
                return ivy;
            }
        }
        if (offline) {
            info("离线模式：缺少 " + coordinate + " (" + extension + ")");
            return null;
        }
        // 3. 远程下载
        for (String base : repositories) {
            String url = base + coordinate.relativePath(extension);
            if (download(url, cached)) {
                return cached;
            }
        }
        warn("无法解析 " + coordinate + " (" + extension + ")，已尝试 "
                + repositories.size() + " 个仓库");
        return null;
    }

    private File findInIvyCache(MavenCoordinate coordinate, String extension) {
        String home = System.getProperty("user.home");
        if (home == null) {
            return null;
        }
        File cache = new File(home, ".ivy2/cache/" + coordinate.groupId + "/" + coordinate.artifactId);
        if (!cache.isDirectory()) {
            return null;
        }
        String fileName = coordinate.fileName(extension);
        File[] candidates = {
                new File(cache, (extension.equals("pom") ? "poms/" : "jars/") + fileName),
                new File(cache, fileName)
        };
        for (File candidate : candidates) {
            if (candidate.isFile() && candidate.length() > 0) {
                return candidate;
            }
        }
        return null;
    }

    /** 下载到目标文件；成功返回 true。 */
    public boolean download(String url, File target) {
        if (url == null || target == null) {
            return false;
        }
        if (url.startsWith("file:")) {
            try {
                File source = new File(new java.net.URI(url));
                if (!source.isFile()) {
                    return false;
                }
                copyFile(source, target);
                return true;
            } catch (Throwable error) {
                warn("复制本地文件失败 " + url + " · " + error);
                return false;
            }
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // 可能是普通文件路径
            File source = new File(decodeUrl(url));
            if (source.isFile()) {
                try {
                    copyFile(source, target);
                    return true;
                } catch (Throwable error) {
                    warn("复制本地文件失败 " + url + " · " + error);
                }
            }
            return false;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(encodeUrl(url)).openConnection();
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "scala-android-sbt-resolver/1.0");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                if (location != null) {
                    return download(location, target);
                }
            }
            if (code != 200) {
                return false;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                warn("无法创建目录 " + parent);
                return false;
            }
            File temp = new File(target.getParentFile(), target.getName() + ".part");
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            }
            if (temp.length() <= 0) {
                temp.delete();
                return false;
            }
            if (target.exists() && !target.delete()) {
                warn("无法覆盖 " + target);
            }
            if (!temp.renameTo(target)) {
                copyFile(temp, target);
                temp.delete();
            }
            info("下载 " + target.getName());
            return target.isFile() && target.length() > 0;
        } catch (Throwable error) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    /** 把 URL 里的空格等字符编码（sbt 脚本里手写的 URL 常常带空格）。 */
    private static String encodeUrl(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        String prefix = url.substring(0, schemeEnd + 3);
        String rest = url.substring(schemeEnd + 3);
        StringBuilder builder = new StringBuilder(prefix);
        for (String segment : rest.split("/", -1)) {
            if (builder.charAt(builder.length() - 1) != '/') {
                builder.append('/');
            }
            builder.append(segment.replace(" ", "%20"));
        }
        return builder.toString();
    }

    private static String decodeUrl(String url) {
        try {
            return java.net.URLDecoder.decode(url, "UTF-8");
        } catch (Throwable ignored) {
            return url;
        }
    }

    private void info(String message) {
        if (log != null) {
            log.line(message);
        }
    }

    private void warn(String message) {
        if (log != null) {
            log.line("! " + message);
        }
    }

    /** 保留给"从 zip 读 POM"的场景：字节 → String。 */
    public static String readText(File file) {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), "UTF-8");
        } catch (Throwable error) {
            return null;
        }
    }

    /** 去重用的工具。 */
    public static Set<String> newOrderedSet() {
        return new LinkedHashSet<>();
    }

    /** URL 里出现中文时的兜底编码。 */
    public static String encoded(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Throwable ignored) {
            return text;
        }
    }
}
