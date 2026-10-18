package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.manifest.ManifestMergeParams;
import com.rapid.api.project.toolchain.android.manifest.ManifestMerger;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.util.ManifestInfo;

import java.io.File;
import java.util.List;

/**
 * 「合并清单」：用宿主的 {@code Tool.MANIFEST_MERGER} 把工程清单与各 AAR 的
 * {@code AndroidManifest.xml} 合并，并按 build.sbt 里的值覆盖
 * applicationId / versionCode / versionName / minSdk / targetSdk。
 *
 * <p>值本身来自本插件的 sbt 解析器（{@link SbtAndroidConfig}）；清单里的
 * 同名属性作为兜底（manifest 里没写就用 build.sbt 的，反之亦然）。
 */
public class MergeManifestTask extends ScalaAndroidTask {

    public static final String ID = "mergeManifest";
    public static final String NAME = "合并清单文件";
    public static final String MERGED_MANIFEST = "AndroidManifest.xml";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new java.util.ArrayList<>();
        File main = project == null || project.getAndroidConfig() == null
                ? null : project.getAndroidConfig().manifestFile;
        if (main != null) {
            inputs.add(main);
        }
        if (project != null && project.getResolution() != null) {
            inputs.addAll(project.getResolution().aarManifests);
        }
        // 清单缺失要让它真正跑起来（execute 里会报出可读错误），不能判"跳过"
        if (main == null || !main.isFile()) {
            alwaysRun();
            return;
        }
        decideState(inputs, new File(getIntermediatePath(MERGED_MANIFEST)), true);
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
        File mainManifest = config.manifestFile;
        if (mainManifest == null || !mainManifest.isFile()) {
            throw fail("找不到 AndroidManifest.xml：" + mainManifest);
        }
        ManifestMerger merger = getTool(Tool.MANIFEST_MERGER);
        if (merger == null) {
            throw fail("宿主工具链里没有清单合并器（ManifestMerger）");
        }

        ManifestInfo mainInfo = ManifestInfo.parse(mainManifest);
        String applicationId = config.applicationId;
        int minSdk = config.minSdk;
        int targetSdk = config.targetSdk;
        int versionCode = config.versionCode;
        String versionName = config.versionName;

        ManifestMergeParams params = new ManifestMergeParams();
        params.mainManifestFile = mainManifest;
        SbtResolutionResult resolution = scalaProject.getResolution();
        List<File> libraryManifests = resolution == null
                ? java.util.Collections.<File>emptyList() : resolution.aarManifests;
        params.libraryManifestFiles = libraryManifests.toArray(new File[0]);
        params.overridePackage = applicationId;
        params.overrideVersionCode = String.valueOf(versionCode);
        params.overrideVersionName = versionName;
        params.overrideMinSdk = String.valueOf(minSdk);
        params.overrideTargetSdk = String.valueOf(targetSdk);
        // 不要把 applicationId / packageName 塞进 placeholders：
        // 宿主的 ManifestMerger2.merge() 会自己往占位符表里放这两个键
        // （packageName、applicationId 都取自 overridePackage），
        // 我们再放一遍会让它的 ImmutableMap.Builder 报
        // "Multiple entries with same key: applicationId=… and applicationId=…"。
        // 需要 ${applicationId} 的地方由 overridePackage 覆盖即可。
        params.placeholders = null;

        File output = new File(getIntermediatePath(MERGED_MANIFEST));
        mkdirs(output.getParentFile());
        params.mergedManifestFile = output;

        info("主清单 " + mainManifest.getName()
                + (mainInfo == null || mainInfo.packageName == null
                        ? "" : "（package=" + mainInfo.packageName + "）")
                + " · 依赖清单 " + libraryManifests.size() + " 个"
                + " · applicationId=" + applicationId
                + " versionCode=" + versionCode + " versionName=" + versionName
                + " minSdk=" + minSdk + " targetSdk=" + targetSdk);
        merger.merge(params, callback());
        if (!output.isFile()) {
            throw fail("清单合并没有产出文件：" + output);
        }
    }
}
