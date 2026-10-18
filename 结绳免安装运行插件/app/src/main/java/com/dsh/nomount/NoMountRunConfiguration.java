package com.dsh.nomount;

import android.util.Log;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.TaskContainer;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 运行配置「免安装运行」。
 *
 * <p><b>任务链完全复用宿主自己的</b>：{@code compileWithTiecodec → compileProjectResources →
 * linkAppResources → mergeManifest → compileWithJavac → dexClassWithD8 → mergeDexWithD8 →
 * generateApk → signAPK → apkAlign}（由 {@link #getTaskId()} 指定的宿主任务
 * {@code apkAlign} 自动带出依赖）。和「调试打包运行」唯一的区别是最后一步：
 * 不调 {@code pm install}，而是把产物交给 {@link ApkRuntime} 在结绳进程内直接加载并显示。
 */
public class NoMountRunConfiguration extends TaskRunConfiguration {

    public static final String ID = "nomount-run";
    public static final String NAME = "免安装运行";
    /** 宿主 cn.tiecode.android.project.task.zipalign.ZipAlignTask.ID（打包链最后一环）。 */
    private static final String HOST_LAST_TASK = "apkAlign";
    /** 宿主 cn.tiecode.android.project.task.gen.GenAPKTask.ID，从这里取产物路径。 */
    private static final String HOST_APK_TASK = "generateApk";

    private final Project project;

    public NoMountRunConfiguration(Project project) {
        this.project = project;
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
    public String getTaskId() {
        return HOST_LAST_TASK;
    }

    @Override
    public ProjectVariant getVariant() {
        return ProjectVariant.DEBUG;
    }

    @Override
    public Project getProject() {
        return project;
    }

    @Override
    public void prepare(ReadyPresentation presentation) {
        presentation.ready(false);
    }

    @Override
    public void onStart() {
        log("免安装运行：复用宿主构建链，构建完成后不安装，直接在结绳进程内显示界面");
    }

    @Override
    public void onResult(ExecutorResult result) {
        if (result != ExecutorResult.SUCCESS) {
            log("构建失败，未运行");
            return;
        }
        String apk = findApkPath();
        if (apk == null) {
            log("构建成功但找不到产物 APK");
            return;
        }
        log("构建成功：" + apk);
        NoMountPlugin.runApk(apk, null);
    }

    /** 宿主 GenAPKTask 上就有产物路径，优先用它；拿不到再按变体/工程名精确找，最后才扫目录。 */
    private String findApkPath() {
        try {
            TaskContainer tasks = project.getTasks();
            if (tasks != null) {
                Object gen = tasks.getTask(HOST_APK_TASK);
                if (gen == null) {
                    try {
                        Class<?> genClass = Class.forName(
                                "cn.tiecode.android.project.task.gen.GenAPKTask");
                        gen = tasks.getTask((Class) genClass);
                    } catch (Throwable ignore) {
                    }
                }
                if (gen != null) {
                    Object value = Reflect.call(gen.getClass(), gen, "getApkFile");
                    if (value instanceof File && ((File) value).isFile()) {
                        return ((File) value).getAbsolutePath();
                    }
                    Log.w(NoMountPlugin.TAG, "generateApk 任务尚未产出文件: " + value);
                } else {
                    Log.w(NoMountPlugin.TAG, "工程里没有 generateApk 任务(" + HOST_APK_TASK + ")");
                }
            }
        } catch (Throwable t) {
            Log.w(NoMountPlugin.TAG, "从 generateApk 任务取产物失败", t);
        }
        return scanOutputs();
    }

    /** 兜底：先按「build/outputs/generateApk/<变体>/<工程名>.apk」精确找，再全目录取最新。 */
    private String scanOutputs() {
        try {
            String variant = getVariant() == ProjectVariant.RELEASE ? "release" : "debug";
            File exact = new File(project.getProjectDir(),
                    "build/outputs/generateApk/" + variant + "/" + project.getName() + ".apk");
            if (exact.isFile()) {
                return exact.getAbsolutePath();
            }
            File outputs = new File(project.getProjectDir(), "build/outputs");
            return newestApk(outputs, 0);
        } catch (Throwable t) {
            Log.w(NoMountPlugin.TAG, "扫描产物目录失败", t);
            return null;
        }
    }

    /** 递归找目录下最新的 .apk。 */
    private String newestApk(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 4) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        String best = null;
        long bestTime = -1L;
        for (File child : children) {
            if (child.isDirectory()) {
                String nested = newestApk(child, depth + 1);
                if (nested != null && new File(nested).lastModified() > bestTime) {
                    bestTime = new File(nested).lastModified();
                    best = nested;
                }
            } else if (child.getName().endsWith(".apk") && child.lastModified() > bestTime) {
                bestTime = child.lastModified();
                best = child.getAbsolutePath();
            }
        }
        return best;
    }

    private void log(String text) {
        Log.i(NoMountPlugin.TAG, text);
        try {
            getLogger().info(text);
        } catch (Throwable ignored) {
        }
    }
}
