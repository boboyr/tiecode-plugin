package com.scala.android.dependency;

import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.sbt.SbtModuleId;
import com.scala.android.sbt.SbtResolver;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 sbt 脚本里解析出来的 {@code libraryDependencies} 变成可用的 classpath。
 *
 * <h3>为什么不用 aether</h3>
 * 宿主的 Java 插件里有一套基于 Eclipse Aether 的 Maven 解析器，但它是
 * {@code studio.java.*} 的实现类、且编译期依赖 aether 的类文件。本插件要能
 * <b>独立</b>完成依赖下载（哪怕宿主没装 Java 插件），所以自己实现了 Maven 解析的
 * 可用子集：
 * <ul>
 *   <li>下载并解析 POM（含 parent 继承、properties 插值、dependencyManagement）；</li>
 *   <li>BFS 传递依赖 + "最近者优先"版本仲裁（与 Maven 一致）；</li>
 *   <li>尊重 scope（compile/runtime/provided/test）与 {@code <optional>}；</li>
 *   <li>尊重 sbt 的 {@code exclude(...)} 与 POM 的 {@code <exclusions>}；</li>
 *   <li>本地 m2 缓存优先、离线可用；AAR 会自动解包成 类/资源/清单/jni/assets。</li>
 * </ul>
 */
public final class SbtDependencyResolver {

    /** 解析参数。 */
    public static final class Options {
        /** 可写的本地仓库目录（m2 布局）。 */
        public File cacheDir;
        /** AAR 解包根目录。 */
        public File unpackDir;
        /** 离线：不访问网络，只查本地仓库。 */
        public boolean offline;
        /** 是否把 test 作用域依赖也算进来。 */
        public boolean includeTest;
        /** 是否把 provided 作用域依赖算进 classpath。 */
        public boolean includeProvided = true;
        /** 传递依赖最大深度。 */
        public int maxDepth = 16;
        /** 额外仓库。 */
        public final List<String> extraRepositories = new ArrayList<>();
        /** 没有本地缓存时用的默认仓库（阿里云镜像优先，便于国内网络）。 */
        public boolean useDefaultRepositories = true;
    }

    public interface Log {
        void line(String message);
    }

    /** 队列里的一个待解析节点。 */
    private static final class Node {
        MavenCoordinate coordinate;
        Set<String> exclusions;
        int depth;
        String scope;
        boolean optional;
        boolean direct;
    }

    private final Options options;
    private final Log log;
    private final SbtResolutionResult result = new SbtResolutionResult();
    /** 已选定的版本（key → version），实现"最近者优先"。 */
    private final Map<String, String> selected = new LinkedHashMap<>();
    /** POM 解析缓存。 */
    private final Map<String, MavenPom> pomCache = new LinkedHashMap<>();
    private MavenRepositoryClient client;

    public SbtDependencyResolver(Options options, Log log) {
        this.options = options == null ? new Options() : options;
        this.log = log;
    }

    public SbtResolutionResult resolve(SbtAndroidConfig config) {
        if (options.cacheDir == null) {
            options.cacheDir = new File(System.getProperty("java.io.tmpdir"), "sbt-m2");
        }
        if (options.unpackDir == null) {
            options.unpackDir = new File(options.cacheDir, "unpacked");
        }
        configureClient(config);

        Deque<Node> queue = new ArrayDeque<>();
        List<SbtModuleId> direct = options.includeTest
                ? config.dependencies : config.compileDependencies();
        for (SbtModuleId module : direct) {
            // 本地文件依赖（build.sbt 里声明 from "@" / "file(/…)" 等形式）：
            // 直接读文件，不进 Maven 解析流程。
            if (module.filePath != null && !module.filePath.isEmpty()) {
                processFileDependency(module, config.baseDir);
                continue;
            }
            if (!module.isResolvable()) {
                result.failures.add(String.valueOf(module) + " · 坐标不完整");
                continue;
            }
            Node node = new Node();
            node.coordinate = MavenCoordinate.parse(
                    module.toMavenCoordinate(scalaBinaryVersion(config)));
            if (node.coordinate == null) {
                result.failures.add(String.valueOf(module) + " · 坐标无法解析");
                continue;
            }
            node.depth = 0;
            node.scope = module.configuration == null ? "compile" : module.configuration;
            node.direct = true;
            node.exclusions = new LinkedHashSet<>(module.excludes);
            queue.add(node);
        }

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            try {
                process(node, queue);
            } catch (Throwable error) {
                result.failures.add(node.coordinate + " · " + error);
                info("! 解析 " + node.coordinate + " 失败：" + error);
            }
        }

        // 本地 jar 兜底：Maven 坐标在仓库/缓存里拉不到时，
        // 到工程 lib/ 目录（unmanagedBase）找 坐标名-version.jar，找到按
        // provided 处理（只进编译 classpath、不进 dex）。
        // 结绳宿主 API（studio-api）就是用这条路：不发布到 Maven，随工程模板下发。
        resolveLocalJarFallback(config);
        collectUnmanagedJars(config);
        return result;
    }

    /**
     * Maven 坐标在本机找不到时的本地兜底：
     * 扫 {@code lib/} 里名为 {@code <artifactId>-<version>.jar}（或带 classifier）的文件。
     * 命中的按该依赖的 scope 归类（模板里 studio-api 是 provided → 只编译不进包），
     * 并记下绝对路径，{@link #collectUnmanagedJars} 会把这些 jar 从
     * unmanagedJars（会进 dex 的那份）里扣掉，避免同一个文件既在 providedJars
     * 又在 unmanagedJars 里。
     */
    private void resolveLocalJarFallback(SbtAndroidConfig config) {
        File libDir = config.unmanagedLibDir;
        if (libDir == null || !libDir.isDirectory()) {
            return;
        }
        List<File> allJars = new ArrayList<>();
        collectJars(libDir, allJars);
        if (allJars.isEmpty()) {
            return;
        }
        for (SbtModuleId module : config.dependencies) {
            if (module == null || module.name == null || module.revision == null) {
                continue;
            }
            String baseArtifact = module.mavenArtifactId(scalaBinaryVersion(config));
            String fileName = baseArtifact + "-" + module.revision + ".jar";
            File hit = findJar(allJars, fileName, baseArtifact);
            if (hit == null) {
                continue;
            }
            String canonical;
            try {
                canonical = hit.getCanonicalFile().getAbsolutePath();
            } catch (Throwable error) {
                canonical = hit.getAbsolutePath();
            }
            result.claimedLocalJars.add(canonical);
            String scope = module.configuration == null ? "compile" : module.configuration;
            if ("provided".equalsIgnoreCase(scope)) {
                result.providedJars.add(hit);
            } else if ("test".equalsIgnoreCase(scope)) {
                result.testJars.add(hit);
            } else {
                result.jars.add(hit);
            }
            result.resolvedCoordinates.add(module + " (local jar)");
            info("本地 jar 兜底命中：" + hit.getName() + " ← " + module);
        }
    }

    private static File findJar(List<File> jars, String exactName, String baseArtifact) {
        for (File file : jars) {
            if (file.getName().equals(exactName)) {
                return file;
            }
        }
        for (File file : jars) {
            String name = file.getName();
            if (name.endsWith(".jar")
                    && name.startsWith(baseArtifact + "-")
                    && !name.endsWith("-sources.jar")
                    && !name.endsWith("-javadoc.jar")) {
                return file;
            }
        }
        return null;
    }

    /**
     * 本地文件依赖：把 {@code module.filePath} 读出来，按作用域放进
     * provided/test/普通 jar 三类（与 Maven 坐标的处理一致）。
     */
    private void processFileDependency(SbtModuleId module, java.io.File baseDir) {
        File file = new File(module.filePath);
        if (!file.isAbsolute() && baseDir != null) {
            file = new File(baseDir, module.filePath);
        }
        if (!file.isFile()) {
            result.failures.add(module + " · 本地文件不存在：" + file);
            info("! 本地文件依赖 " + module + " 未找到：" + file);
            return;
        }
        String scope = module.configuration == null ? "compile" : module.configuration;
        if ("provided".equalsIgnoreCase(scope)) {
            result.providedJars.add(file);
        } else if ("test".equalsIgnoreCase(scope)) {
            result.testJars.add(file);
        } else {
            result.jars.add(file);
        }
        result.resolvedCoordinates.add(module + " (file)");
        info("本地文件依赖纳入 classpath：" + file.getName()
                + "（" + (file.length() / 1024) + " KB，" + scope + "）");
    }

    private String scalaBinaryVersion(SbtAndroidConfig config) {
        String version = config.scalaVersion;
        if (version == null) {
            return "3";
        }
        if (version.startsWith("3.")) {
            return "3";
        }
        int index = version.indexOf('.');
        if (index > 0) {
            int second = version.indexOf('.', index + 1);
            return second > 0 ? version.substring(0, second) : version;
        }
        return version;
    }

    // ------------------------------------------------------------ 仓库

    private void configureClient(SbtAndroidConfig config) {
        client = new MavenRepositoryClient(options.cacheDir);
        client.setOffline(options.offline);
        client.setLog(message -> info(message));
        client.addDefaultLocalRoots();
        // 1) 脚本里声明的仓库优先
        for (SbtResolver resolver : config.resolvers) {
            if (resolver != null && resolver.isValid()) {
                client.addRepository(resolver.url);
            }
        }
        // 2) 额外仓库
        for (String repository : options.extraRepositories) {
            client.addRepository(repository);
        }
        // 3) 默认仓库（国内网络友好）
        if (options.useDefaultRepositories) {
            client.addRepository("https://maven.aliyun.com/repository/public/");
            client.addRepository("https://maven.aliyun.com/repository/google/");
            client.addRepository("https://repo1.maven.org/maven2/");
            client.addRepository("https://maven.google.com/");
            client.addRepository("https://jitpack.io/");
        }
    }

    // ------------------------------------------------------------ 主流程

    private void process(Node node, Deque<Node> queue) {
        MavenCoordinate coordinate = node.coordinate;
        if (node.depth > options.maxDepth) {
            return;
        }
        if (node.exclusions != null && node.exclusions.contains(coordinate.key())) {
            info("按 exclude 跳过 " + coordinate);
            return;
        }
        String already = selected.get(coordinate.key());
        if (already != null) {
            // 最近者优先：先入队（层数更浅）的版本胜出
            if (!already.equals(coordinate.version)) {
                info("版本仲裁：保留 " + coordinate.key() + ":" + already
                        + "，忽略 " + coordinate.version);
            }
            return;
        }
        selected.put(coordinate.key(), coordinate.version);

        MavenPom pom = effectivePom(coordinate);
        String packaging = pom != null && pom.packaging != null && !pom.packaging.isEmpty()
                ? pom.packaging : coordinate.packaging;
        if ("pom".equals(packaging)) {
            result.resolvedCoordinates.add(coordinate + " (pom)");
        } else {
            MavenCoordinate artifactCoordinate = new MavenCoordinate(
                    coordinate.groupId, coordinate.artifactId, coordinate.version, coordinate.classifier);
            artifactCoordinate.packaging = packaging;
            File artifact = client.fetchArtifact(artifactCoordinate);
            if (artifact == null) {
                result.failures.add(coordinate + " · 构件下载失败（" + packaging + "）");
            } else {
                collectArtifact(artifactCoordinate, artifact, packaging, node.scope);
            }
        }

        if (pom == null) {
            result.failures.add(coordinate + " · POM 缺失，无法继续解析传递依赖");
            return;
        }
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        mergePomChain(coordinate, properties, managed);

        for (MavenPom.Dependency dependency : pom.dependencies) {
            if (!isScopeIncluded(dependency.scope, node.direct)) {
                continue;
            }
            // optional 只对"声明它的模块自己"有意义，作为消费者一律不传递（与 Maven 一致）。
            if (dependency.optional) {
                continue;
            }
            String version = dependency.version;
            if (version == null || version.isEmpty()) {
                version = managed.get(dependency.key());
            }
            version = interpolate(version, properties);
            if (version == null || version.isEmpty()) {
                info("跳过无版本依赖 " + dependency.key()
                        + "（dependencyManagement 里也没有约束）");
                continue;
            }
            MavenCoordinate child = new MavenCoordinate(
                    interpolate(dependency.groupId, properties),
                    interpolate(dependency.artifactId, properties),
                    version,
                    dependency.classifier);
            child.packaging = dependency.type == null || dependency.type.isEmpty()
                    ? "jar" : dependency.type;

            Node next = new Node();
            next.coordinate = child;
            next.exclusions = node.exclusions;
            next.depth = node.depth + 1;
            next.scope = dependency.scope;
            next.optional = dependency.optional;
            queue.add(next);
        }
    }

    private boolean isScopeIncluded(String scope, boolean direct) {
        String value = scope == null || scope.isEmpty() ? "compile" : scope.toLowerCase(Locale.ROOT);
        switch (value) {
            case "compile":
            case "runtime":
                return true;
            case "provided":
                return options.includeProvided && direct;
            case "test":
                return options.includeTest && direct;
            case "system":
            case "import":
            default:
                return false;
        }
    }

    private void collectArtifact(MavenCoordinate coordinate, File artifact, String packaging,
                                 String scope) {
        String normalizedScope = scope == null ? "compile" : scope.toLowerCase(Locale.ROOT);
        if ("aar".equalsIgnoreCase(packaging)) {
            File output = new File(options.unpackDir,
                    coordinate.groupId + "_" + coordinate.artifactId + "_" + coordinate.version);
            try {
                AarExtractor.Extracted extracted = AarExtractor.extract(artifact, output);
                boolean onlyCompile = "provided".equals(normalizedScope) || "test".equals(normalizedScope);
                List<File> classTarget = onlyCompile
                        ? ("test".equals(normalizedScope) ? result.testJars : result.providedJars)
                        : result.aarClassesJars;
                if (extracted.classesJar != null) {
                    classTarget.add(extracted.classesJar);
                }
                classTarget.addAll(extracted.libsJars);
                if (extracted.resDir != null) {
                    result.aarResDirs.add(extracted.resDir);
                }
                if (extracted.manifest != null) {
                    result.aarManifests.add(extracted.manifest);
                }
                if (extracted.assetsDir != null) {
                    result.aarAssetsDirs.add(extracted.assetsDir);
                }
                for (Map.Entry<String, List<File>> entry : extracted.jniLibs.entrySet()) {
                    List<File> libraries = result.aarJniLibs.get(entry.getKey());
                    if (libraries == null) {
                        libraries = new ArrayList<>();
                        result.aarJniLibs.put(entry.getKey(), libraries);
                    }
                    libraries.addAll(entry.getValue());
                }
                result.resolvedCoordinates.add(coordinate + " (aar)");
            } catch (Throwable error) {
                result.failures.add(coordinate + " · AAR 解包失败：" + error);
            }
            return;
        }
        if ("provided".equals(normalizedScope)) {
            result.providedJars.add(artifact);
        } else if ("test".equals(normalizedScope)) {
            result.testJars.add(artifact);
        } else {
            result.jars.add(artifact);
        }
        result.resolvedCoordinates.add(coordinate + " (" + normalizedScope + ")");
    }

    private void collectUnmanagedJars(SbtAndroidConfig config) {
        File libDir = config.unmanagedLibDir;
        if (libDir == null || !libDir.isDirectory()) {
            return;
        }
        List<File> found = new ArrayList<>();
        collectJars(libDir, found);
        // 被"本地 jar 兜底"认领过的（命中 provided/compile 坐标的那份）不再进 unmanaged：
        // 它们已经按作用域归好类了，重复进 unmanagedJars 会把 provided 的 jar
        // 再塞回 dex 的 classpath（runtimeClasspath 里不该有 provided）。
        int skipped = 0;
        for (File file : found) {
            String canonical;
            try {
                canonical = file.getCanonicalFile().getAbsolutePath();
            } catch (Throwable error) {
                canonical = file.getAbsolutePath();
            }
            if (result.claimedLocalJars.contains(canonical)) {
                skipped++;
                continue;
            }
            result.unmanagedJars.add(file);
        }
        if (!found.isEmpty()) {
            info("本地 lib/ 目录纳入 classpath：" + found.size() + " 个 jar"
                    + (skipped > 0 ? "（" + skipped + " 个已被坐标认领，按作用域归类）" : ""));
        }
    }

    private void collectJars(File directory, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            if (child.isDirectory()) {
                collectJars(child, out);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                out.add(child);
            }
        }
    }

    // ------------------------------------------------------------ POM

    private MavenPom loadPom(MavenCoordinate coordinate) {
        String key = coordinate.toString();
        if (pomCache.containsKey(key)) {
            return pomCache.get(key);
        }
        MavenPom pom = null;
        File file = client.fetchPom(coordinate);
        if (file != null) {
            byte[] bytes = readBytes(file);
            if (bytes != null) {
                pom = MavenPomParser.parse(bytes);
            }
        }
        pomCache.put(key, pom);
        return pom;
    }

    /** 取 POM 的"有效形态"：补全 parent 带来的 groupId/version/packaging。 */
    private MavenPom effectivePom(MavenCoordinate coordinate) {
        MavenPom pom = loadPom(coordinate);
        if (pom == null) {
            return null;
        }
        if (pom.coordinate == null) {
            pom.coordinate = coordinate;
        }
        if (pom.coordinate.groupId == null || pom.coordinate.groupId.isEmpty()) {
            pom.coordinate = new MavenCoordinate(pom.parentGroupId, pom.coordinate.artifactId,
                    pom.coordinate.version, coordinate.classifier);
        }
        if (pom.coordinate.version == null || pom.coordinate.version.isEmpty()) {
            pom.coordinate = new MavenCoordinate(pom.coordinate.groupId, pom.coordinate.artifactId,
                    pom.parentVersion, coordinate.classifier);
        }
        if (pom.parentVersion != null) {
            pom.properties.put("project.parent.version", pom.parentVersion);
        }
        return pom;
    }

    /**
     * 沿 parent 链自顶向下合并 properties 与 dependencyManagement。
     */
    private void mergePomChain(MavenCoordinate coordinate, Map<String, String> properties,
                               Map<String, String> managed) {
        List<MavenPom> chain = new ArrayList<>();
        MavenPom current = effectivePom(coordinate);
        int guard = 0;
        while (current != null && current.parentVersion != null && guard++ < 10) {
            chain.add(current);
            current = effectivePom(new MavenCoordinate(
                    current.parentGroupId, current.parentArtifactId, current.parentVersion));
        }
        if (current != null) {
            chain.add(current);
        }
        // 自顶向下：子 POM 覆盖父 POM
        for (int i = chain.size() - 1; i >= 0; i--) {
            MavenPom pom = chain.get(i);
            properties.putAll(pom.properties);
            // 内建属性
            if (pom.coordinate != null) {
                putIfNotNull(properties, "project.groupId", pom.coordinate.groupId);
                putIfNotNull(properties, "project.artifactId", pom.coordinate.artifactId);
                putIfNotNull(properties, "project.version", pom.coordinate.version);
                putIfNotNull(properties, "pom.groupId", pom.coordinate.groupId);
                putIfNotNull(properties, "pom.artifactId", pom.coordinate.artifactId);
                putIfNotNull(properties, "pom.version", pom.coordinate.version);
                putIfNotNull(properties, "version", pom.coordinate.version);
            }
            managed.putAll(pom.managedVersions);
        }
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    /** {@code ${xxx}} 插值（最多 5 轮，处理嵌套属性）。 */
    public static String interpolate(String value, Map<String, String> properties) {
        if (value == null || value.indexOf("${") < 0 || properties == null) {
            return value;
        }
        String current = value;
        for (int round = 0; round < 5; round++) {
            int start = current.indexOf("${");
            if (start < 0) {
                break;
            }
            StringBuilder builder = new StringBuilder();
            int index = 0;
            boolean replaced = false;
            while (index < current.length()) {
                int open = current.indexOf("${", index);
                if (open < 0) {
                    builder.append(current, index, current.length());
                    break;
                }
                int close = current.indexOf('}', open + 2);
                if (close < 0) {
                    builder.append(current, index, current.length());
                    break;
                }
                builder.append(current, index, open);
                String name = current.substring(open + 2, close).trim();
                String replacement = properties.get(name);
                if (replacement == null) {
                    builder.append(current, open, close + 1);
                } else {
                    builder.append(replacement);
                    replaced = true;
                }
                index = close + 1;
            }
            String next = builder.toString();
            if (!replaced || next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }

    private static byte[] readBytes(File file) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (Throwable error) {
            return null;
        }
    }

    private void info(String message) {
        if (message == null) {
            return;
        }
        result.logLines.add(message);
        if (log != null) {
            log.line(message);
        }
    }
}
