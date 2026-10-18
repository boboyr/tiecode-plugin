package com.scala.android.dependency;

import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.sbt.SbtModuleId;
import com.scala.android.sbt.SbtResolver;

import java.io.File;

/**
 * 依赖解析器的自检工具：对着一个本地 {@code file://} 仓库跑一遍完整解析
 * （传递依赖、版本冲突、parent+properties、optional/test/provided 过滤、
 * exclude、AAR 解包、lib/ 本地 jar）。
 */
public final class SbtResolverHarness {

    public static void main(String[] args) throws Exception {
        File repository = new File(args.length > 0 ? args[0] : "/tmp/m2repo");
        File workspace = new File(args.length > 1 ? args[1] : "/tmp/resolvertest");

        SbtAndroidConfig config = new SbtAndroidConfig();
        config.scalaVersion = "3.8.4";
        config.resolvers.add(new SbtResolver("local-fixture", repository.toURI().toString()));
        config.unmanagedLibDir = new File(workspace, "lib");

        config.dependencies.add(module("com.example", "liba", "1.0", false, null));
        config.dependencies.add(module("com.example", "libc", "1.0", false, null));
        config.dependencies.add(module("com.example", "libd", "1.0", false, null));
        config.dependencies.add(module("com.example", "libandroid", "1.0", false, null));
        config.dependencies.add(module("com.example", "libskip", "1.0", false, "com.example:libb"));

        SbtDependencyResolver.Options options = new SbtDependencyResolver.Options();
        options.cacheDir = new File(workspace, "m2cache");
        options.unpackDir = new File(workspace, "unpacked");
        options.useDefaultRepositories = false;
        options.offline = false;

        SbtDependencyResolver resolver = new SbtDependencyResolver(options, System.out::println);
        SbtResolutionResult result = resolver.resolve(config);

        System.out.println();
        System.out.println("== " + result.summary());
        System.out.println("-- jar classpath --");
        for (File file : result.classpath()) {
            System.out.println("   " + file.getAbsolutePath() + "  (" + file.length() + " bytes)");
        }
        System.out.println("-- aar 资源目录 --");
        for (File file : result.aarResDirs) {
            System.out.println("   " + file.getAbsolutePath());
        }
        System.out.println("-- aar 清单 --");
        for (File file : result.aarManifests) {
            System.out.println("   " + file.getAbsolutePath());
        }
        System.out.println("-- aar jni --");
        for (String abi : result.aarJniLibs.keySet()) {
            System.out.println("   " + abi + " -> " + result.aarJniLibs.get(abi));
        }
        System.out.println("-- 解析成功坐标 --");
        for (String coordinate : result.resolvedCoordinates) {
            System.out.println("   " + coordinate);
        }
        System.out.println("-- 失败 --");
        for (String failure : result.failures) {
            System.out.println("   " + failure);
        }
    }

    private static SbtModuleId module(String group, String name, String version,
                                      boolean cross, String exclude) {
        SbtModuleId module = new SbtModuleId(group, name, version);
        module.crossVersion = cross;
        if (exclude != null) {
            module.excludes.add(exclude);
        }
        return module;
    }
}
