package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.TaskState;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.aapt.Aapt2;
import com.rapid.api.project.toolchain.android.aapt.Aapt2CompileParams;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「编译项目资源文件」：aapt2 compile，把 {@code src/main/res} 与各 AAR 的 {@code res/}
 * 编成 {@code .flat} 中间产物，供 {@link LinkResTask} 链接。
 *
 * <p>写法对齐结绳本体的 {@code CompileProjectResourcesTask}：从工具链取 {@code Tool.AAPT2}，
 * 填 {@code Aapt2CompileParams} 后调用，选项同为 {@code --no-crunch}。
 *
 * <h3>两个必须注意的点</h3>
 * <ol>
 *   <li>宿主的 {@code Aapt2.compile()} 是把 {@code sourcePaths} <b>直接拼在命令行上</b>的
 *       （不是 {@code --dir}），所以这里必须传<b>一个个资源文件</b>，宿主自己也是
 *       用 {@code getResFiles()} 递归收集文件的；</li>
 *   <li>aapt2 生成的 flat 文件名是 {@code <父目录名>_<文件名>.flat}，
 *       不同资源根下的同名文件（如各自的 {@code drawable/icon.png}）会撞名，
 *       所以每个资源根单独输出到自己的子目录。</li>
 * </ol>
 */
public class Aapt2CompileResTask extends ScalaAndroidTask {

    public static final String ID = "compileProjectResources";
    public static final String NAME = "编译项目资源文件";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new ArrayList<>();
        if (project != null && project.getAndroidConfig() != null
                && project.getAndroidConfig().resDir != null
                && project.getAndroidConfig().resDir.isDirectory()) {
            inputs.add(project.getAndroidConfig().resDir);
        }
        if (project != null && project.getResolution() != null) {
            inputs.addAll(project.getResolution().aarResDirs);
        }
        decideState(inputs, new File(getIntermediatePath("flat")), !inputs.isEmpty());
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
            throw fail("宿主没有可用的 Android SDK 工具链（android.jar）");
        }
        Aapt2 aapt2 = getTool(Tool.AAPT2);
        if (aapt2 == null) {
            throw fail("宿主工具链里没有 aapt2");
        }

        // 资源根：工程自己的 + 各 AAR 的
        List<File> resRoots = new ArrayList<>();
        if (config.resDir != null && config.resDir.isDirectory()) {
            resRoots.add(config.resDir);
        }
        SbtResolutionResult resolution = scalaProject.getResolution();
        if (resolution != null) {
            for (File resDir : resolution.aarResDirs) {
                if (resDir != null && resDir.isDirectory()) {
                    resRoots.add(resDir);
                }
            }
        }
        if (resRoots.isEmpty()) {
            info("没有资源目录，跳过 aapt2 compile");
            setState(TaskState.NO_SOURCE);
            return;
        }

        File flatRoot = new File(getIntermediatePath("flat"));
        cleanDir(flatRoot);
        mkdirs(flatRoot);

        int total = 0;
        int index = 0;
        for (File resRoot : resRoots) {
            List<String> sourcePaths = new ArrayList<>();
            collectResFiles(resRoot, sourcePaths);
            if (sourcePaths.isEmpty()) {
                continue;
            }
            File flatDir = new File(flatRoot, String.valueOf(index++));
            mkdirs(flatDir);

            Aapt2CompileParams params = new Aapt2CompileParams();
            params.flatOutputDirPath = flatDir.getAbsolutePath();
            params.sourcePaths = sourcePaths;
            params.options = new String[]{"--no-crunch"};

            info("资源 " + sourcePaths.size() + " 个 ← " + describe(resRoot));
            int code = aapt2.compile(params, callback());
            if (code != 0) {
                throw fail("aapt2 compile 失败（退出码 " + code
                        + "，资源目录 " + resRoot + "）");
            }
            total += sourcePaths.size();
        }
        if (total == 0) {
            warn("资源目录里没有任何文件");
            setState(TaskState.NO_SOURCE);
            return;
        }
        info("共编译资源 " + total + " 个");
    }

    /** 递归收集资源文件（宿主也是按文件喂 aapt2 的）。 */
    private void collectResFiles(File file, List<String> out) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
            for (File child : children) {
                collectResFiles(child, out);
            }
            return;
        }
        if (file.isFile()) {
            out.add(file.getPath());
        }
    }

    private static String describe(File resRoot) {
        File parent = resRoot.getParentFile();
        return parent == null ? resRoot.getName() : parent.getName() + "/" + resRoot.getName();
    }
}
