package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.zipalign.ZipAlignParams;
import com.rapid.api.project.toolchain.android.zipalign.Zipalign;
import com.scala.android.androidproject.ScalaAndroidProject;

import java.io.File;
import java.util.List;

/**
 * 「对齐 APK」：zipalign 4 字节对齐（与结绳本体的 {@code ZipAlignTask} 一致）。
 * 对齐后 APK 才算最终产物，交给 {@link BuildApkTask} 拷到输出目录。
 */
public class ZipAlignApkTask extends ScalaAndroidTask {

    public static final String ID = "zipAlignScala";
    public static final String NAME = "对齐APK";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new java.util.ArrayList<>();
        if (project != null) {
            com.rapid.api.project.task.Task packageApk = project.getTasks().getTask(PackageApkTask.ID);
            if (packageApk != null) {
                inputs.add(new File(packageApk.getIntermediatePath(project.getName()
                        + "-unsigned.apk")));
            }
        }
        decideState(inputs, new File(getIntermediatePath(project == null
                ? "app-aligned.apk" : project.getName() + "-aligned.apk")), true);
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
        Zipalign zipalign = getTool(Tool.ZIPALIGN);
        if (zipalign == null) {
            throw fail("宿主工具链里没有 zipalign");
        }
        File input = new File(project.getTasks().getTask(PackageApkTask.ID)
                .getIntermediatePath(project.getName() + "-unsigned.apk"));
        if (!input.isFile()) {
            throw fail("没有待对齐的 APK：" + input);
        }
        File output = new File(getIntermediatePath(project.getName() + "-aligned.apk"));
        mkdirs(output.getParentFile());
        if (output.exists() && !output.delete()) {
            warn("无法删除旧的对齐产物 " + output);
        }

        ZipAlignParams params = new ZipAlignParams();
        params.alignByteLength = "4";
        params.inputApkPath = input.getAbsolutePath();
        params.outputApkPath = output.getAbsolutePath();

        int code = zipalign.alignApk(params, callback());
        if (code != 0 || !output.isFile()) {
            throw fail("zipalign 失败（退出码 " + code + "）");
        }
        info("对齐完成 " + output.getName() + "（" + output.length() + " bytes）");
    }
}
