package com.scala.android.sbt;

import java.io.File;

/**
 * sbt 解析器的命令行自检工具（不参与插件打包，只在开发机上跑）。
 *
 * <pre>
 * javac -d out $(find app/src/main/java/com/scala/android/sbt -name '*.java') tests/.../SbtParserHarness.java
 * java -cp out com.scala.android.sbt.SbtParserHarness /path/to/project-with-build.sbt
 * </pre>
 */
public final class SbtParserHarness {

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        System.out.println("== 解析目录: " + root.getAbsolutePath());
        SbtBuild build = SbtBuildScriptLoader.load(root);
        if (build == null) {
            System.out.println("!! 目录下没有 .sbt 文件");
            return;
        }
        System.out.println("sbt.version = " + build.sbtVersion);
        System.out.println("scalaVersion = " + build.scalaVersion()
                + " (binary " + build.scalaBinaryVersion() + ")");
        System.out.println("脚本文件: ");
        for (File file : build.scriptFiles) {
            System.out.println("  - " + file.getName());
        }
        System.out.println("sbt 插件: ");
        for (SbtPlugin plugin : build.plugins) {
            System.out.println("  - " + plugin + (plugin.isAndroidPlugin() ? "  [android]" : ""));
        }
        System.out.println("子工程: ");
        for (SbtProjectModel project : build.projects) {
            System.out.println("  - " + project + " plugins=" + project.enabledPlugins);
        }

        SbtAndroidConfig config = SbtAndroidConfig.from(build);
        System.out.println("-- Android 配置 --");
        System.out.println("  " + config.summary());
        System.out.println("  applicationId  = " + config.applicationId);
        System.out.println("  versionName    = " + config.versionName + " (" + config.versionCode + ")");
        System.out.println("  minSdk/target  = " + config.minSdk + " / " + config.targetSdk
                + " (compile " + config.compileSdk + ")");
        System.out.println("  scalaSourceDir = " + config.scalaSourceDir);
        System.out.println("  resDir         = " + config.resDir);
        System.out.println("  manifest       = " + config.manifestFile);
        System.out.println("  keystore       = " + config.releaseKeystore
                + " alias=" + config.releaseKeystoreAlias);
        System.out.println("  scalacOptions  = " + config.scalacOptions);
        System.out.println("  仓库:");
        for (SbtResolver resolver : config.resolvers) {
            System.out.println("    * " + resolver);
        }
        System.out.println("  依赖:");
        for (SbtModuleId module : config.dependencies) {
            System.out.println("    * " + module + "  →  " + module.toMavenCoordinate(build.scalaBinaryVersion()));
        }
        System.out.println("-- 诊断 (" + build.issues.size() + ") --");
        for (String line : build.issueLines()) {
            System.out.println("  " + line);
        }
    }
}
