package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.aapt.Aapt2;
import com.rapid.api.project.toolchain.android.aapt.Aapt2LinkParams;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「链接资源」：aapt2 link，产出
 * <ul>
 *   <li>{@code res.ap_}：资源 + 清单的可打包形态（APK 里 res/* + resources.arsc）；</li>
 *   <li>{@code R.java}：资源 ID 类，交给 javac 编译；</li>
 *   <li>{@code aapt_rules.txt}：给 proguard 用（本插件暂不做混淆，仅产出）。</li>
 * </ul>
 *
 * <p>逐项对齐结绳本体的 {@code LinkResourcesTask}：{@code --auto-add-overlay}、
 * {@code -I android.jar}、AAR 包名走 {@code --extra-packages}、assets 目录随参数传入。
 */
public class LinkResTask extends ScalaAndroidTask {

    public static final String ID = "linkAppResources";
    public static final String NAME = "链接资源文件";

    public static final String RES_APK_NAME = "res.ap_";
    public static final String PROGUARD_RULES = "aapt_rules.txt";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new java.util.ArrayList<>();
        if (project != null) {
            com.rapid.api.project.task.Task compileRes = project.getTasks()
                    .getTask(Aapt2CompileResTask.ID);
            if (compileRes != null) {
                inputs.add(new File(compileRes.getIntermediatePath("flat")));
            }
            com.rapid.api.project.task.Task mergeManifest = project.getTasks()
                    .getTask(MergeManifestTask.ID);
            if (mergeManifest != null) {
                inputs.add(new File(mergeManifest.getIntermediatePath(
                        MergeManifestTask.MERGED_MANIFEST)));
            }
            if (project.getAndroidConfig() != null) {
                inputs.add(project.getAndroidConfig().assetsDir);
            }
            if (project.getResolution() != null) {
                inputs.addAll(project.getResolution().aarAssetsDirs);
            }
        }
        decideState(inputs, new File(getIntermediatePath(RES_APK_NAME)), true);
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
        AndroidSdk sdk = getTool(Tool.ANDROID_SDK);
        if (sdk == null) {
            throw fail("宿主没有可用的 Android SDK 工具链");
        }
        Aapt2 aapt2 = getTool(Tool.AAPT2);
        if (aapt2 == null) {
            throw fail("宿主工具链里没有 aapt2");
        }
        SbtResolutionResult resolution = scalaProject.getResolution();

        File flatDir = new File(project.getTasks().getTask(Aapt2CompileResTask.ID)
                .getIntermediatePath("flat"));
        Set<String> flatFiles = new LinkedHashSet<>();
        collectFiles(flatDir, flatFiles);

        Aapt2LinkParams params = new Aapt2LinkParams();
        params.sdkPath = sdk.getAndroidJarPath();
        params.outputApkPath = getIntermediatePath(RES_APK_NAME);
        params.outputRJavaDir = getGeneratedDir().getPath();
        params.outputProguardRuleDir = getIntermediatePath(PROGUARD_RULES);
        params.minSdkVersion = String.valueOf(config.minSdk);
        params.targetSdkVersion = String.valueOf(config.targetSdk);
        params.versionCode = String.valueOf(config.versionCode);
        params.versionName = config.versionName == null ? "1.0" : config.versionName;
        params.inputFlatFiles = flatFiles;

        File merged = new File(project.getTasks().getTask(MergeManifestTask.ID)
                .getIntermediatePath(MergeManifestTask.MERGED_MANIFEST));
        params.inputManifestPath = (merged.isFile() ? merged : config.manifestFile).getAbsolutePath();

        List<String> assetsDirs = new ArrayList<>();
        if (config.assetsDir != null && config.assetsDir.isDirectory()) {
            assetsDirs.add(config.assetsDir.getAbsolutePath());
        }
        if (resolution != null) {
            for (File dir : resolution.aarAssetsDirs) {
                if (dir != null && dir.isDirectory()) {
                    assetsDirs.add(dir.getAbsolutePath());
                }
            }
        }
        params.inputAssetsDirs = assetsDirs;

        // AAR 的包名要作为 extra package，否则资源引用会报找不到
        Set<String> extraPackages = new LinkedHashSet<>();
        if (resolution != null) {
            for (File manifest : resolution.aarManifests) {
                String packageName = com.scala.android.util.ManifestInfo.packageOf(manifest);
                if (packageName != null && !packageName.isEmpty()
                        && !packageName.equals(config.applicationId)) {
                    extraPackages.add(packageName);
                }
            }
        }
        params.extraPackages = extraPackages;
        params.options = new String[]{"--auto-add-overlay", "--no-version-vectors"};

        mkdirs(new File(params.outputApkPath).getParentFile());
        mkdirs(getGeneratedDir());
        info("flat " + flatFiles.size() + " 个 · assets " + assetsDirs.size()
                + " 个 · extraPackages " + extraPackages.size()
                + " → " + RES_APK_NAME + " / R.java");
        int code = aapt2.link(params, callback());
        if (code != 0) {
            throw fail("aapt2 link 失败（退出码 " + code + "）");
        }
    }

    private void collectFiles(File directory, Set<String> out) {
        File[] children = directory == null ? null : directory.listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, out);
            } else if (child.getName().endsWith(".flat")) {
                out.add(child.getAbsolutePath());
            }
        }
    }
}
