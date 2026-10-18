package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.util.file.FileUtils;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.sbt.TiecodePluginKeys;

import java.io.File;
import java.util.List;

/**
 * 「构建结绳插件」：整条 APK 链的收口任务 —— 把签名对齐后的 APK 改后缀成
 * <b>{@code .rmx}（结绳插件包）</b>，输出到
 * {@code build/outputs/plugins/<variant>/<工程名>.rmx}，并校验
 * {@code META-INF/program.json} 确实随包打进去了（结绳装载器靠它识别插件）。
 *
 * <p>对应结绳本体的 {@code GenerateQiPluginTask}：把 {@code <工程名>.apk} 拷成
 * {@code <工程名>.rmx}。本插件在拷贝之外多做一步 {@code program.json} 存在性检查，
 * 缺了会给出可读错误而不是产出"结绳打不开"的坏 rmx。
 *
 * <pre>
 * … → buildApk → buildPlugin（本任务）
 * </pre>
 */
public class BuildPluginTask extends ScalaAndroidTask {

    public static final String ID = "buildPlugin";
    public static final String NAME = "构建结绳插件";

    /** 产物所在子目录（与 apk 输出目录并列）。 */
    public static final String OUTPUT_SUBDIR = "plugins";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        if (project == null) {
            alwaysRun();
            return;
        }
        // 输入必须是「构建APK」真正的产物：build/outputs/apk/<variant>/<工程名>.apk
        // （不是 buildApk.getOutputPath() —— 那是 build/outputs/buildApk/<variant>/）。
        File apk = new File(project.apkOutputDir(), project.getName() + ".apk");
        File target = new File(pluginOutputDir(), project.getName() + ".rmx");
        decideState(List.of(apk), target, true);
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
        // requireProject() 保证下面两个产物路径都能算出来
        requireProject();
        // 「构建APK」的产物：build/outputs/apk/<variant>/<工程名>.apk
        File apk = builtApkFile();
        if (!apk.isFile() || apk.length() <= 0) {
            throw fail("没有可打包的 APK：" + apk
                    + "（先执行「构建APK」或「调试打包运行」）");
        }
        // 校验：结绳新世代插件必须带 META-INF/program.json，否则装载器认不出
        if (!containsProgramJson(apk)) {
            throw fail("APK 里没有 " + TiecodePluginKeys.PROGRAM_JSON_RELATIVE
                    + "，结绳无法识别为插件。"
                    + "请确认工程里存在 src/main/resources/META-INF/program.json，"
                    + "并且「生成APK」任务已把它打进包。");
        }
        File target = builtPluginFile();
        mkdirs(target.getParentFile());
        try {
            FileUtils.copyFile(apk, target);
        } catch (Throwable error) {
            throw fail("拷贝 rmx 到输出目录失败：" + error);
        }
        info("rmx 已生成 → " + target.getAbsolutePath()
                + "（" + target.length() / 1024 + " KB，含 META-INF/program.json）");
    }

    /** 产物目录：{@code build/outputs/plugins/<variant>/}。 */
    public File pluginOutputDir() {
        ScalaAndroidProject scalaProject = project == null ? null : project;
        if (scalaProject == null) {
            return new File("build/outputs/plugins/release");
        }
        return scalaProject.pluginOutputDir();
    }

    /** 检查 APK 里是否带 {@code META-INF/program.json}（纯 java.util.zip，不解包）。 */
    private static boolean containsProgramJson(File apk) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry entry =
                    zip.getEntry("META-INF/program.json");
            return entry != null;
        } catch (Throwable error) {
            // 读不动就当没有
            return false;
        }
    }
}
