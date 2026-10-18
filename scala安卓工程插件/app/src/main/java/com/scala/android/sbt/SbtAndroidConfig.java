package com.scala.android.sbt;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@link SbtBuild} 里抽出「Android 工程」需要的配置。
 *
 * <p>键名同时兼容两套常见插件：
 * <ul>
 *   <li>{@code sbt-modern-android}（scala-android-app-modern 用的那套）：
 *       {@code platformTarget / androidApplicationId / androidMinSdk / androidTargetSdk /
 *       androidVersionCode / androidVersionName / androidReleaseKeystore ...}；</li>
 *   <li>老的 {@code android-sdk-plugin / sbt-android}：{@code applicationId / minSdkVersion ...}。</li>
 * </ul>
 * 目录约定与 {@code scala-android-app-modern} 一致：
 * {@code src/main/scala}、{@code src/main/res}、{@code src/main/assets}、
 * {@code src/main/jniLibs}、{@code src/main/AndroidManifest.xml}。
 */
public final class SbtAndroidConfig {

    public String name;
    public String organization;
    public String version;
    public String scalaVersion;
    public String sbtVersion;
    public boolean androidPluginPresent;
    /** 被选中当作"应用工程"的子工程 id（多模块时是 app 而不是 root）。 */
    public String androidProjectId;

    public String applicationId;
    public String versionName = "1.0";
    public int versionCode = 1;
    public int minSdk = 26;
    public int targetSdk = 35;
    public int compileSdk = 35;

    public File releaseKeystore;
    public String releaseKeystoreAlias;
    public String releaseKeystorePassword;
    public String releaseKeyPassword;

    public File baseDir;
    public File scalaSourceDir;
    public File javaSourceDir;
    public File resDir;
    public File assetsDir;
    public File jniLibsDir;
    public File manifestFile;
    /** {@code lib/} 下的本地 jar（sbt 的 unmanagedBase 默认就是它）。 */
    public File unmanagedLibDir;
    /** 是否结绳插件工程（见 {@link TiecodePluginKeys}）。 */
    public boolean isTiecodePlugin;

    public final List<String> scalacOptions = new ArrayList<>();
    public final List<SbtModuleId> dependencies = new ArrayList<>();
    public final List<SbtResolver> resolvers = new ArrayList<>();

    /** 解析过程中的全部诊断。 */
    public final List<SbtIssue> issues = new ArrayList<>();

    public static SbtAndroidConfig from(SbtBuild build) {
        SbtAndroidConfig config = new SbtAndroidConfig();
        config.issues.addAll(build.issues);
        config.sbtVersion = build.sbtVersion;
        config.androidPluginPresent = build.hasAndroidPlugin();

        SbtProjectModel project = build.androidProject();
        if (project == null) {
            project = build.mainProject();
        }
        File base = project != null && project.baseDir != null ? project.baseDir : build.buildRoot;
        config.baseDir = base;

        config.name = firstNonEmpty(
                build.getString(project, "name"),
                base.getName());
        config.organization = build.getString(project, "organization");
        config.version = build.getString(project, "version");
        config.scalaVersion = build.scalaVersion();

        config.applicationId = firstNonEmpty(
                build.getString(project, "androidApplicationId"),
                build.getString(project, "applicationId"),
                build.getString(project, "android/applicationId"),
                config.organization != null && config.name != null
                        ? config.organization + "." + sanitize(config.name) : null,
                "com.example.app");

        config.versionName = firstNonEmpty(
                build.getString(project, "androidVersionName"),
                build.getString(project, "versionName"),
                config.version,
                "1.0");
        config.versionCode = firstNonNull(
                build.getInt(project, "androidVersionCode"),
                build.getInt(project, "versionCode"),
                1);
        config.minSdk = firstNonNull(
                build.getInt(project, "androidMinSdk"),
                build.getInt(project, "minSdk"),
                build.getInt(project, "minSdkVersion"),
                26);
        config.targetSdk = firstNonNull(
                build.getInt(project, "androidTargetSdk"),
                build.getInt(project, "targetSdk"),
                build.getInt(project, "targetSdkVersion"),
                35);
        config.compileSdk = firstNonNull(
                parseApiLevel(build.getString(project, "platformTarget")),
                build.getInt(project, "compileSdkVersion"),
                config.targetSdk);

        config.releaseKeystore = firstFile(
                build.getFile(project, "androidReleaseKeystore"),
                build.getFile(project, "releaseKeystore"));
        config.releaseKeystoreAlias = firstNonEmpty(
                build.getString(project, "androidReleaseKeystoreAlias"),
                build.getString(project, "releaseKeystoreAlias"));
        config.releaseKeystorePassword = firstNonEmpty(
                build.getString(project, "androidReleaseKeystorePassword"),
                build.getString(project, "releaseKeystorePassword"));
        config.releaseKeyPassword = firstNonEmpty(
                build.getString(project, "androidReleaseKeyPassword"),
                build.getString(project, "releaseKeyPassword"));

        config.scalaSourceDir = firstDir(
                build.getFile(project, "Compile/scalaSource"),
                build.getFile(project, "scalaSource"),
                new File(base, "src/main/scala"));
        config.javaSourceDir = firstDir(
                build.getFile(project, "Compile/javaSource"),
                new File(base, "src/main/java"));
        config.resDir = firstDir(
                build.getFile(project, "androidRes"),
                build.getFile(project, "Compile/resourceDirectory"),
                new File(base, "src/main/res"));
        config.assetsDir = firstDir(
                build.getFile(project, "androidAssets"),
                new File(base, "src/main/assets"));
        config.jniLibsDir = firstDir(
                build.getFile(project, "androidJniLibs"),
                new File(base, "src/main/jniLibs"));
        config.manifestFile = firstFile(
                build.getFile(project, "androidManifest"),
                new File(base, "src/main/AndroidManifest.xml"));
        config.unmanagedLibDir = firstDir(
                build.getFile(project, "Compile/unmanagedBase"),
                build.getFile(project, "unmanagedBase"),
                new File(base, "lib"));

        config.scalacOptions.addAll(build.scalacOptions(project));
        config.dependencies.addAll(build.dependencies(project));
        config.resolvers.addAll(build.resolvers(project));
        config.androidProjectId = project == null ? null : project.id;
        config.normalizePaths();
        // 结绳插件工程识别（build.sbt 键 / sbt 插件 / AndroidManifest meta-data）
        String manifestText = null;
        if (config.manifestFile != null && config.manifestFile.isFile()) {
            try {
                java.io.FileInputStream input = new java.io.FileInputStream(config.manifestFile);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                input.close();
                manifestText = new String(out.toByteArray(), "UTF-8");
            } catch (Throwable ignored) {
                // 读不动就当没有
            }
        }
        config.isTiecodePlugin = TiecodePluginKeys.isTiecodePluginProject(build, manifestText);
        return config;
    }

    /** 去掉 {@code file(".")} 带出来的 {@code /.} 之类的冗余段。 */
    private void normalizePaths() {
        baseDir = normalize(baseDir);
        scalaSourceDir = normalize(scalaSourceDir);
        javaSourceDir = normalize(javaSourceDir);
        resDir = normalize(resDir);
        assetsDir = normalize(assetsDir);
        jniLibsDir = normalize(jniLibsDir);
        manifestFile = normalize(manifestFile);
        unmanagedLibDir = normalize(unmanagedLibDir);
        releaseKeystore = normalize(releaseKeystore);
    }

    private static File normalize(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.toPath().normalize().toFile();
        } catch (Throwable ignored) {
            return file;
        }
    }

    /** 需要下载的依赖（去掉本地 lib 和 TEST/PROVIDED 这类不参与打包的）。 */
    public List<SbtModuleId> runtimeDependencies() {
        List<SbtModuleId> result = new ArrayList<>();
        for (SbtModuleId module : dependencies) {
            if (module.participatesInRuntime()) {
                result.add(module);
            }
        }
        return result;
    }

    public List<SbtModuleId> compileDependencies() {
        List<SbtModuleId> result = new ArrayList<>();
        for (SbtModuleId module : dependencies) {
            if (module.participatesInCompile()) {
                result.add(module);
            }
        }
        return result;
    }

    public int effectiveMinSdk(int sdkFromToolchain) {
        return Math.max(minSdk, sdkFromToolchain);
    }

    /**
     * {@code platformTarget := "android-35"} → 35；也接受 {@code "35"} 或 {@code "android-33"}。
     */
    public static Integer parseApiLevel(String platformTarget) {
        if (platformTarget == null) {
            return null;
        }
        String digits = platformTarget.replaceAll("[^0-9]", " ").trim();
        if (digits.isEmpty()) {
            return null;
        }
        String first = digits.split("\\s+")[0];
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 供日志使用的一行摘要。 */
    public String summary() {
        return name + " (" + applicationId + ") · Scala " + scalaVersion
                + " · minSdk " + minSdk + " / targetSdk " + targetSdk
                + " · 依赖 " + dependencies.size() + " 条 · 仓库 " + resolvers.size() + " 个";
    }

    private static String sanitize(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            } else if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '.') {
                builder.append('.');
            }
        }
        while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '.') {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static int firstNonNull(Integer... values) {
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return 0;
    }

    private static File firstFile(File... values) {
        for (File value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static File firstDir(File... values) {
        for (File value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
