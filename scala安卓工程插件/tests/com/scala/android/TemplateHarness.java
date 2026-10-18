package com.scala.android;

import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.sbt.SbtBuild;
import com.scala.android.sbt.SbtBuildScriptLoader;
import com.scala.android.sbt.SbtPlugin;
import com.scala.android.util.ZipUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板自检：把 {@code assets/templates/project/scala_android_sbt.zip} 解包成一个工程，
 * 再交给真实的 sbt 解析器去读，验证"模板生成的工程一定能被识别"。
 *
 * <p>覆盖设备上暴露过的问题：模板必须落地 {@code project/build.properties}；
 * 而 {@code project/plugins.sbt} 按设计**不再生成** —— 识别安卓工程要靠
 * {@code enablePlugins(...)} 或脚本里的 {@code android*} 键。
 */
public final class TemplateHarness {

    private static final String PACKAGE_NAME = "com.example.myapp";

    public static void main(String[] args) throws Exception {
        File zip = new File(args.length > 0
                ? args[0] : "app/src/main/assets/templates/project/scala_android_sbt.zip");
        File target = new File(args.length > 1 ? args[1] : "/tmp/template-check");
        deleteRecursively(target);
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IllegalStateException("无法创建 " + target);
        }

        System.out.println("== 解包模板 " + zip.getName());
        List<String> written = ZipUtils.unzip(zip, target);

        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("package_name", PACKAGE_NAME);
        replacements.put("app_name", "我的应用");
        replacements.put("project_name", "我的scala安卓工程");
        replacements.put("organization", "com.example");
        replacements.put("scala_version", "3.8.4");
        replacements.put("min_sdk", "33");
        replacements.put("target_sdk", "35");
        replacements.put("platform_target", "android-35");
        replacements.put("version_name", "1.0.0");
        int replaced = ZipUtils.replacePlaceholders(target, replacements);
        System.out.println("   文件 " + written.size() + " 个 · 替换 " + replaced + " 个");

        File sample = new File(target, "src/main/scala/pkg/MainActivity.scala");
        if (sample.isFile()) {
            File dest = new File(target, "src/main/scala/"
                    + PACKAGE_NAME.replace('.', '/') + "/MainActivity.scala");
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            java.nio.file.Files.move(sample.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(new File(target, "src/main/scala/pkg"));
        }

        List<String> required = new ArrayList<>();
        required.add("build.sbt");
        required.add("project/build.properties");
        required.add("src/main/AndroidManifest.xml");
        required.add("src/main/scala/" + PACKAGE_NAME.replace('.', '/') + "/MainActivity.scala");
        required.add("src/main/res/layout/activity_main.xml");
        required.add("release-test.keystore");
        boolean ok = true;
        for (String path : required) {
            File file = new File(target, path);
            boolean exists = file.isFile() && file.length() > 0;
            ok &= exists;
            System.out.println("   " + (exists ? "[ok]  " : "[缺失]") + " " + path);
        }

        System.out.println("== 交给 sbt 解析器读一遍");
        SbtBuild build = SbtBuildScriptLoader.load(target);
        if (build == null) {
            System.out.println("!! 解析器读不到构建脚本");
            System.exit(2);
        }
        System.out.println("   sbt.version    = " + build.sbtVersion);
        System.out.println("   scalaVersion   = " + build.scalaVersion());
        System.out.println("   脚本           = " + build.scriptFiles.size() + " 个");
        for (SbtPlugin plugin : build.plugins) {
            System.out.println("   sbt 插件       = " + plugin
                    + (plugin.isAndroidPlugin() ? "  [android]" : ""));
        }
        SbtAndroidConfig config = SbtAndroidConfig.from(build);
        System.out.println("   applicationId  = " + config.applicationId);
        System.out.println("   minSdk/target  = " + config.minSdk + " / " + config.targetSdk);
        System.out.println("   androidPlugin  = " + config.androidPluginPresent
                + "（无 project/plugins.sbt）");
        System.out.println("   诊断           = " + build.issues.size() + " 条");

        boolean pass = ok
                && build.sbtVersion != null
                && build.plugins.isEmpty()
                && config.androidPluginPresent
                && PACKAGE_NAME.equals(config.applicationId)
                && build.issues.isEmpty();
        System.out.println(pass ? "== 模板自检通过" : "== 模板自检失败");
        if (!pass) {
            for (String line : build.issueLines()) {
                System.out.println("   " + line);
            }
            System.exit(3);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
