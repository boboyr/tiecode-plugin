package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.util.ApkPacker;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「生成APK」：把 res.ap_（资源+清单）、dex、assets、jni 组装成 APK。
 *
 * <h3>为什么不用 {@code Tool.APK_GENERATOR}</h3>
 * 结绳宿主注册的工具里<b>没有 APK_GENERATOR</b>（只有 AAPT2/D8/MANIFEST_MERGER/JAVA_COMPILER/
 * ZIPALIGN/APK_SIGNER/R8/PROGUARD…，打包那一步是宿主 {@code GenerateAPKTask} 自己写的），
 * 所以改成和结绳本体一样：<b>用 {@link ApkPacker} 自己打包，对齐/签名交给宿主的
 * {@code Tool.ZIPALIGN} / {@code Tool.APK_SIGNER}</b>。
 *
 * <p>产物是不带签名的 APK（{@code <工程名>-unsigned.apk}），随后
 * {@link ZipAlignApkTask} 对齐、{@link SignApkTask} 用 apksigner 做 v1+v2+v3 签名。
 */
public class PackageApkTask extends ScalaAndroidTask {

    public static final String ID = "packageApk";
    public static final String NAME = "生成APK";

    public static final String UN_SIGNED_APK = "-unsigned.apk";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new ArrayList<>();
        if (project != null) {
            Task linkRes = project.getTasks().getTask(LinkResTask.ID);
            if (linkRes != null) {
                inputs.add(new File(linkRes.getIntermediatePath(LinkResTask.RES_APK_NAME)));
            }
            Task dex = project.getTasks().getTask(DexTask.ID);
            if (dex != null) {
                inputs.add(new File(dex.getIntermediatePath("dex")));
            }
            if (project.getAndroidConfig() != null) {
                inputs.add(project.getAndroidConfig().assetsDir);
                inputs.add(project.getAndroidConfig().jniLibsDir);
                // src/main/resources 直接被打进 APK 根目录（program.json 就在这里）：
                // 不算进输入的话，只改清单会被判"无需执行"，包里还是旧清单。
                inputs.add(new File(project.getAndroidConfig().baseDir, "src/main/resources"));
            }
            if (project.getResolution() != null) {
                inputs.addAll(project.getResolution().aarAssetsDirs);
                inputs.addAll(flatten(project.getResolution().aarJniLibs));
            }
        }
        decideState(inputs, new File(getIntermediatePath(project == null
                ? "app" + UN_SIGNED_APK : project.getName() + UN_SIGNED_APK)), true);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void execute() throws TaskInterruptError {
        ScalaAndroidProject scalaProject = requireProject();
        SbtAndroidConfig config = scalaProject.getAndroidConfig();
        if (config == null) {
            throw fail("build.sbt 还没解析");
        }

        File resApk = new File(project.getTasks().getTask(LinkResTask.ID)
                .getIntermediatePath(LinkResTask.RES_APK_NAME));
        if (!resApk.isFile()) {
            throw fail("缺少资源链接产物 " + resApk);
        }

        // dex：classes.dex → classes2.dex → …
        List<File> dexFiles = new ArrayList<>();
        File dexDir = new File(project.getTasks().getTask(DexTask.ID).getIntermediatePath("dex"));
        File[] children = dexDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && child.getName().endsWith(".dex")) {
                    dexFiles.add(child);
                }
            }
        }
        if (dexFiles.isEmpty()) {
            throw fail("没有 dex 产物（D8 任务失败了？）");
        }
        dexFiles = ApkPacker.sortDexFiles(dexFiles);

        // native：工程 jniLibs + AAR 的 jni
        Map<String, List<File>> nativeLibs = new LinkedHashMap<>();
        collectNativeLibs(config.jniLibsDir, nativeLibs);
        SbtResolutionResult resolution = scalaProject.getResolution();
        if (resolution != null) {
            for (Map.Entry<String, List<File>> entry : resolution.aarJniLibs.entrySet()) {
                List<File> libraries = nativeLibs.get(entry.getKey());
                if (libraries == null) {
                    libraries = new ArrayList<>();
                    nativeLibs.put(entry.getKey(), libraries);
                }
                libraries.addAll(entry.getValue());
            }
        }

        // java resources（src/main/resources）→ APK 根目录。
        // 注意：assets 不能放这里，它已经由 aapt2 link 的 -A 打进 res.ap_ 的 assets/ 了
        List<File> javaResourceDirs = new ArrayList<>();
        File javaRes = new File(config.baseDir, "src/main/resources");
        if (javaRes.isDirectory()) {
            javaResourceDirs.add(javaRes);
        }

        File output = new File(getIntermediatePath(project.getName() + UN_SIGNED_APK));
        try {
            ApkPacker.pack(output, resApk, dexFiles, nativeLibs, javaResourceDirs,
                    message -> info(message));
        } catch (Throwable error) {
            throw fail("打包 APK 失败：" + error);
        }
        if (!output.isFile() || output.length() <= 0) {
            throw fail("打包 APK 没有产出文件：" + output);
        }
        info("dex " + dexFiles.size() + " 个 · native " + ApkPacker.describeNativeLibs(nativeLibs)
                + " · javaRes " + javaResourceDirs.size() + " 个"
                + " → " + output.getName() + "（" + output.length() + " bytes）");
    }

    private void collectNativeLibs(File jniLibsDir, Map<String, List<File>> out) {
        if (jniLibsDir == null || !jniLibsDir.isDirectory()) {
            return;
        }
        File[] abis = jniLibsDir.listFiles();
        if (abis == null) {
            return;
        }
        for (File abi : abis) {
            if (!abi.isDirectory()) {
                continue;
            }
            List<File> libraries = new ArrayList<>();
            File[] files = abi.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".so")) {
                        libraries.add(file);
                    }
                }
            }
            if (!libraries.isEmpty()) {
                out.put(abi.getName(), libraries);
            }
        }
    }

    private static List<File> flatten(Map<String, List<File>> map) {
        List<File> result = new ArrayList<>();
        for (List<File> list : map.values()) {
            result.addAll(list);
        }
        return result;
    }
}
