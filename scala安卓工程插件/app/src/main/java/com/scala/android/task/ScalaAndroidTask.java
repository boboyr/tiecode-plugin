package com.scala.android.task;

import com.rapid.api.Platform;
import com.rapid.api.framework.ServiceKeys;
import com.rapid.api.framework.project.toolchain.ToolchainService;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.TaskState;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.framework.data.Key;
import com.scala.android.androidproject.ScalaAndroidProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Scala 安卓工程的任务基类。
 *
 * <p>与结绳本体的 {@code studio.java.android.project.task.AndroidTask} 同构：
 * 持有工程与工具链服务，供子类用 {@link #getTool(Key)} 拿 aapt2 / d8 / apksigner /
 * zipalign / javac 这些宿主已经装好的工具。
 */
public abstract class ScalaAndroidTask extends BaseTask {

    protected ScalaAndroidProject project;
    protected ToolchainService tools;

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        if (p instanceof ScalaAndroidProject) {
            this.project = (ScalaAndroidProject) p;
        }
    }

    /** 取工程；没有工程时抛任务中断（所有任务都必须在工程里执行）。 */
    protected ScalaAndroidProject requireProject() throws TaskInterruptError {
        if (project == null) {
            throw fail("没有打开 Scala 安卓工程");
        }
        return project;
    }

    /** 取宿主工具链里的工具；拿不到返回 null，由调用方给出可读的错误。 */
    protected <T extends Tool> T getTool(Key<T> key) {
        if (tools == null) {
            tools = Platform.getService(ServiceKeys.TOOLCHAIN);
        }
        if (tools == null) {
            tools = Platform.getService(com.rapid.api.framework.project.ProjectServiceKeys.TOOLCHAIN);
        }
        return tools == null ? null : tools.getTool(key);
    }

    /** 工具日志 → 构建日志。 */
    protected Tool.OnExecutionCallback callback() {
        return (level, line) -> {
            if (logger == null || line == null) {
                return;
            }
            switch (level) {
                case WARNING:
                    logger.warning(line);
                    break;
                case ERROR:
                    logger.error(line);
                    break;
                default:
                    logger.info(line);
                    break;
            }
        };
    }

    protected void info(String message) {
        if (logger != null) {
            logger.info(getName() + "：" + message);
        }
    }

    /**
     * 记一条错误并中断任务。
     *
     * <p>宿主的 {@code DefaultTaskExecutor} 在异步路径里只会打印
     * {@code 任务: … ==> … 执行中断}，<b>不会打印 {@code TaskInterruptError} 的消息</b>，
     * 所以失败原因必须先自己写进构建日志，否则用户只看到"中断"而不知道原因。
     */
    protected TaskInterruptError fail(String message) {
        if (logger != null) {
            logger.error(getName() + "：失败：" + message);
        }
        return new TaskInterruptError(message);
    }

    protected void warn(String message) {
        if (logger != null) {
            logger.warning(getName() + "：" + message);
        }
    }


    // ------------------------------------------------------------ 任务状态

    /**
     * 统一决定本次要不要执行（宿主执行器就是靠 {@code prepare()} 里的状态决定
     * 打印"任务: 工程 ==> 名字"、加"(跳过)/(无需执行)"、还是真的执行）。
     *
     * <p><b>每个任务都必须在 prepare() 里显式设置状态</b>：任务实例在工程打开时创建一次、
     * 整个会话复用，上一次构建留下的 NO_SOURCE/UP_TO_DATE 会一直粘着。
     *
     * @param inputs   全部输入（没有输入 → 任务显示"(跳过)"）
     * @param output   产物（比所有输入都新 → 任务显示"(无需执行)"）
     * @param hasInput 是否真的有输入；false 表示这条任务本次无需参与
     */
    protected void decideState(boolean hasInput, List<File> inputs, File output) {
        if (!hasInput) {
            setState(TaskState.NO_SOURCE);
            return;
        }
        if (isUpToDate(output, withBuildScript(inputs))) {
            setState(TaskState.UP_TO_DATE);
            return;
        }
        setState(TaskState.PREPARE);
    }

    protected void decideState(List<File> inputs, File output, boolean hasInput) {
        decideState(hasInput, inputs, output);
    }

    /** 显式要求执行（不做增量判断）。 */
    protected void alwaysRun() {
        setState(TaskState.PREPARE);
    }

    /** build.sbt 一改，所有增量判断都失效（脚本里什么东西都可能变）。 */
    protected List<File> withBuildScript(List<File> inputs) {
        List<File> result = inputs == null ? new ArrayList<>() : new ArrayList<>(inputs);
        File script = project == null ? null : project.buildScript();
        if (script != null && script.isFile()) {
            result.add(script);
        }
        return result;
    }

    /**
     * 产物是否比所有输入都新。
     *
     * <p>目录按"递归取最新修改时间"比较：往编译输出目录里改动过的 .class
     * 只会更新文件自身的 mtime，目录 mtime 不变，所以必须递归。
     */
    protected static boolean isUpToDate(File output, List<File> inputs) {
        if (output == null || !output.exists()) {
            return false;
        }
        if (output.isFile() && output.length() <= 0) {
            return false;
        }
        long outputTime = newest(output);
        if (outputTime <= 0) {
            return false;
        }
        if (!output.isFile()) {
            File[] children = output.listFiles();
            if (children == null || children.length == 0) {
                return false;
            }
        }
        for (File input : inputs) {
            if (input == null || !input.exists()) {
                continue;
            }
            if (newest(input) > outputTime) {
                return false;
            }
        }
        return true;
    }

    /** 递归取最新修改时间（目录取自身与全部后代的最大值）。 */
    protected static long newest(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.lastModified();
        }
        long result = file.lastModified();
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                result = Math.max(result, newest(child));
            }
        }
        return result;
    }

    /** 把依赖解析结果的 classpath 里的真实文件收集出来（用于增量判断）。 */
    protected static List<File> existingOnly(List<File> files) {
        List<File> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file != null && file.exists()) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 目录工具

    /**
     * 「构建APK」真正写出的产物：{@code build/outputs/apk/<variant>/<工程名>.apk}
     * （见 {@link BuildApkTask#execute()} 与 {@link ScalaAndroidProject#apkOutputDir()}）。
     *
     * <p><b>不要用 {@code buildApk.getOutputPath(...)} 代替</b>：宿主 {@code BaseTask}
     * 是按任务 id 推导输出目录的（{@code build/outputs/buildApk/<variant>/}），
     * 和本插件自定义的 apk 输出目录不是同一个地方，下游任务用它就会"找不到 APK"。
     */
    protected File builtApkFile() throws TaskInterruptError {
        ScalaAndroidProject p = requireProject();
        return new File(p.apkOutputDir(), p.getName() + ".apk");
    }

    /**
     * 「构建结绳插件」真正写出的产物：
     * {@code build/outputs/plugins/<variant>/<工程名>.rmx}
     * （见 {@link BuildPluginTask#execute()} 与 {@link ScalaAndroidProject#pluginOutputDir()}）。
     * 同样不能用 {@code buildPlugin.getOutputPath(...)} 代替，理由见 {@link #builtApkFile()}。
     */
    protected File builtPluginFile() throws TaskInterruptError {
        ScalaAndroidProject p = requireProject();
        return new File(p.pluginOutputDir(), p.getName() + ".rmx");
    }

    protected static void mkdirs(File directory) throws TaskInterruptError {
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new TaskInterruptError("无法创建目录：" + directory);
        }
    }

    /** 清空目录（增量构建时保证"删掉的源文件不会残留在产物里"）。 */
    protected static void cleanDir(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    protected static void deleteRecursively(File file) {
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
