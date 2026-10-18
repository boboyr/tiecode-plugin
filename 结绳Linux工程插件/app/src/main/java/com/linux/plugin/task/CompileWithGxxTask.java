package com.linux.plugin.task;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.linux.plugin.lang.engine.TiecodeLanguageContext;
import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.project.ProjectServiceKeys;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.util.android.AssetUtils;
import com.rapid.util.android.EnvUtils;
import com.rapid.util.file.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.tiecode.api.TiecodeApiKeys;
import cn.tiecode.api.tool.Gxx;
import cn.tiecode.api.tool.GxxCompileParams;
import com.linux.plugin.tool.GxxCompile;

/**
 * 调用 Gxx 工具链编译结绳生成的 C++ 源码,并链接 SDL3 动态库。
 *
 * <p>流程:
 * <ol>
 *   <li>收集 {@link TieCompileTask} 产出的 .h/.cpp,以及各绳包 {@code 源代码} 目录下的 .cpp
 *       (如 {@code sdl3_tie.cpp}:编译器不一定复制这些外部源文件);</li>
 *   <li>把插件 assets 中的 {@code libSDL3.so} 复制到 Linux 临时目录;</li>
 *   <li>用 {@code -shared -fPIC} 编译并以 {@code -L<tmp> -lSDL3} 链接,产出 {@code libmain.so}。</li>
 * </ol>
 */
public class CompileWithGxxTask extends BaseTask {

    private static final String COMPILE_TASK_ID = TieCompileTask.ID;

    /** 最终产物:共享库 libmain.so。 */
    private static final String OUTPUT_NAME = "libmain.so";

    /** 插件 assets 中的 SDL3 动态库文件名。 */
    private static final String SDL3_ASSET_NAME = "libSDL3.so";

    /** 链接时使用的库名(对应 -lSDL3)。 */
    private static final String SDL3_LINK_NAME = "SDL3";

    /** unity build:把多个 .cpp 合并为单个翻译单元编译,避免重复解析头文件(实测 13s -> 1.6s)。 */
    private static final boolean USE_UNITY_BUILD = true;
    /** 合并后生成的源文件名(放在 Linux 临时目录,不会被输入扫描重复采集)。 */
    private static final String UNITY_SOURCE_NAME = "unity_build.cpp";

    @Override
    public void prepare(Project project, ProjectVariant variant, Logger logger) {
        // 必须调用父类:它负责初始化 task 上下文,否则 getIntermediatePath() 返回 null,
        // 下面的 Os.setenv 会抛出空指针异常,导致 execute() 无法执行。
        super.prepare(project, variant, logger);
        try {
            Os.setenv("TEMP", getIntermediatePath(), false);
        } catch (ErrnoException e) {
            logger.trace(e);
        } catch (Throwable e) {
            logger.trace(e);
        }
    }

    @Override
    public void execute() throws TaskInterruptError {
        List<String> inputs = collectInputs();
        if (inputs.isEmpty()) {
            throw new TaskInterruptError("没有任何输入文件:请先执行「" + TieCompileTask.NAME + "」");
        }

        Gxx gxx = new GxxCompile();
		//Platform.getService(ProjectServiceKeys.TOOLCHAIN).getTool(TiecodeApiKeys.GXX);
        if (!gxx.isInstalled(Platform.getApplicationContext())) {
            try {
                logger.info("安装 Gxx 编译工具链...");
                gxx.install(Platform.getApplicationContext());
            } catch (IOException e) {
                logger.trace(e);
                throw new TaskInterruptError("Gxx 编译工具链安装失败");
            }
        }

        File tmpDir = getLinuxTmpDir();
        copySdl3Lib(tmpDir);

        String[] options = buildOptions(tmpDir);
        // 链接器输出必须是具体文件,不能是目录;写到 Linux tmp 目录,
        // 与 CopyBinFiletask 读取的 <linux>/tmp/libmain.so 保持一致。
        File outputFile = new File(tmpDir, OUTPUT_NAME);

        // unity build:多输入时合并为单个翻译单元,显著减少头文件重复解析。
        List<String> compileInputs = inputs;
        if (USE_UNITY_BUILD && inputs.size() > 1) {
            File unity = buildUnitySource(inputs, tmpDir);
            if (unity != null) {
                compileInputs = Collections.singletonList(unity.getAbsolutePath());
            }
        }

        GxxCompileParams params = new GxxCompileParams();
        params.inputFiles = compileInputs;
        params.outputPath = outputFile.getAbsolutePath();
        params.options = options;

        logger.info("编译到目标平台,输入文件数: " + compileInputs.size() + "(原始 " + inputs.size() + ")");
        logger.info("输出路径: " + outputFile.getAbsolutePath());
        //logger.info("编译参数: " + String.join(" ", options));

        // 编译前删除旧产物,避免"上次遗留文件"冒充本次编译成功。
        if (outputFile.exists()) {
            boolean deleted = outputFile.delete();
            logger.info("删除旧产物: " + outputFile.getAbsolutePath() + " -> " + deleted);
        }

        compileGxxOnExecutionCallback callback = new compileGxxOnExecutionCallback(logger);
        int code = gxx.compile(params, callback);
        if (code != 0) {
            // 把 gxx 的输出一并抛出,避免只看到“编译失败”而看不到原因。
            String detail = callback.tail(80);
            logger.error("Gxx 返回码: " + code);
            if (!detail.isEmpty()) {
                logger.error("Gxx 输出:\n" + detail);
            }
            throw new TaskInterruptError("编译到目标平台失败(返回码 " + code + ")\n" + detail);
        }

        if (!outputFile.exists()) {
            throw new TaskInterruptError("Gxx 编译成功但未生成产物: " + outputFile.getAbsolutePath()
                    + "\nGxx 输出:\n" + callback.tail(80));
        }
        logger.info("编译完成,产物: " + outputFile.getAbsolutePath() + " (" + outputFile.length() + " 字节)");

        // 把产物同步到任务中间目录,便于在 build/intermediates/compileWithGxx 下查看。
        publishArtifacts(outputFile, tmpDir);
    }

    /** 把 Gxx 产物(libmain.so 与依赖的 libSDL3.so)复制到任务中间目录。 */
    private void publishArtifacts(File outputFile, File tmpDir) {
        try {
            File destDir = new File(getIntermediatePath());
            if (!destDir.exists() && !destDir.mkdirs()) {
                logger.warning("创建中间目录失败: " + destDir);
                return;
            }
            FileUtils.copyFile(outputFile, new File(destDir, OUTPUT_NAME));
            File sdl3 = new File(tmpDir, SDL3_ASSET_NAME);
            if (sdl3.exists()) {
                FileUtils.copyFile(sdl3, new File(destDir, SDL3_ASSET_NAME));
            }
            logger.info("产物已同步到: " + destDir.getAbsolutePath());
        } catch (Throwable t) {
            logger.trace(t);
        }
    }

    /** 组装 Gxx 编译参数:编译选项 + 头文件路径 + SDL3 链接参数。 */
    private String[] buildOptions(File tmpDir) {
        List<String> options = new ArrayList<>();
        options.add("-w");
        // 生成共享库 libmain.so,需要 -shared 与 -fPIC;不再使用 -pie。
        options.add("-shared");
        options.add("-fPIC");
        options.add("-Wno-deprecated-declarations");

        // 头文件搜索路径:中间目录 + 各绳包目录(含 sdl3_tie.h / SDL3 头文件)
        for (String include : collectIncludeDirs()) {
            options.add("-I" + include);
        }
        options.add("-I" + tmpDir.getAbsolutePath());

        // 链接 SDL3:-L 指向已复制 libSDL3.so 的临时目录,-lSDL3 链接该库
        options.add("-L" + tmpDir.getAbsolutePath());
        options.add("-l" + SDL3_LINK_NAME);
        // 写入绝对 rpath,保证产物运行时能找到 libSDL3.so(避免 $ORIGIN 被外层 shell 展开)
        options.add("-Wl,-rpath," + tmpDir.getAbsolutePath());
        return options.toArray(new String[0]);
    }

    /** 把插件 assets 中的 libSDL3.so 复制到 Linux 临时目录。 */
    private void copySdl3Lib(File tmpDir) throws TaskInterruptError {
        File target = new File(tmpDir, SDL3_ASSET_NAME);
        if (target.exists() && target.length() > 0) {
            return;
        }
        try {
            Context context = AndroidPlugins.getContext(this);
            AssetUtils.copyAsset(context, SDL3_ASSET_NAME, target);
            logger.info("已复制 " + SDL3_ASSET_NAME + " 到 " + target.getAbsolutePath());
        } catch (Throwable e) {
            throw new TaskInterruptError("复制 " + SDL3_ASSET_NAME + " 失败: " + e);
        }
    }

    /**
     * 生成 unity build 源文件:用绝对路径 {@code #include} 各 .cpp,合并为单个翻译单元。
     *
     * <p>绝对路径包含可保证各 .cpp 内部的 {@code #include "xxx.h"} 仍按各自所在目录解析,
     * 因此无需改动原文件、也无需额外 -I。生成物放在 Linux 临时目录,避免被输入扫描重复采集。
     *
     * @return 生成的 unity 文件;失败返回 null(调用方回退逐文件编译)。
     */
    private File buildUnitySource(List<String> inputs, File tmpDir) {
        try {
            File unity = new File(tmpDir, UNITY_SOURCE_NAME);
            StringBuilder sb = new StringBuilder();
            sb.append("// 由 CompileWithGxxTask 自动生成:unity build,合并编译以加速。\n");
            sb.append("// 各实现文件用绝对路径包含,其内部 #include \"xxx.h\" 仍按各自目录解析。\n");
            for (String in : inputs) {
                if (in == null) {
                    continue;
                }
                String name = new File(in).getName();
                if (name.equals(UNITY_SOURCE_NAME)) {
                    continue; // 防御:绝不包含自身
                }
                sb.append("#include \"").append(in.replace('\\', '/')).append("\"\n");
            }
            FileUtils.writeFile(sb.toString(), unity);
            return unity;
        } catch (Throwable t) {
            logger.warning("生成 unity build 源文件失败,回退逐文件编译: " + t);
            return null;
        }
    }

    /** 收集编译输入:结绳编译产物 + 各绳包源代码目录下的 .cpp。 */
    private List<String> collectInputs() {
        Set<String> inputs = new LinkedHashSet<>();
        Task compileTask = Platform.getProject().getTasks().getTask(COMPILE_TASK_ID);
        if (compileTask != null) {
            // 只把 .cpp 作为编译输入;头文件通过 -I 搜索,不作为源文件传入。
            // 兼容两种输出结构:中间目录本身,以及其 src 子目录。
            scan(compileTask.getIntermediatePath(), inputs, false);
            scan(compileTask.getIntermediatePath("src"), inputs, false);
        }
        // 绳包源代码目录中的 .cpp(编译器可能不复制,如 sdl3_tie.cpp)
        for (File libRoot : listLibRoots()) {
            scan(new File(libRoot, "源代码").getAbsolutePath(), inputs, false);
        }
        return new ArrayList<>(inputs);
    }

    /** 收集头文件搜索路径:各绳包中所有含头文件的目录。 */
    private List<String> collectIncludeDirs() {
        Set<String> dirs = new LinkedHashSet<>();
        File projectDir = getProjectDir();
        if (projectDir != null) {
            File srcDir = new File(projectDir, "源代码");
            if (srcDir.isDirectory()) {
                dirs.add(srcDir.getAbsolutePath());
            }
        }
        for (File libRoot : listLibRoots()) {
            collectHeaderDirs(libRoot, dirs);
        }
        return new ArrayList<>(dirs);
    }

    /** 递归收集包含头文件的目录。 */
    private void collectHeaderDirs(File dir, Set<String> out) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        boolean hasHeader = false;
        for (File file : files) {
            if (file.isDirectory()) {
                collectHeaderDirs(file, out);
            } else if (isHeader(file.getName())) {
                hasHeader = true;
            }
        }
        if (hasHeader) {
            out.add(dir.getAbsolutePath());
        }
    }

    private void scan(String dir, Set<String> out, boolean includeHeader) {
        if (dir == null) {
            return;
        }
        File[] files = new File(dir).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name.endsWith(".cpp") || (includeHeader && isHeader(name))) {
                out.add(file.getAbsolutePath());
            }
        }
    }

    private static boolean isHeader(String name) {
        return name.endsWith(".h") || name.endsWith(".hpp") || name.endsWith(".hxx");
    }

    private List<File> listLibRoots() {
        return TiecodeLanguageContext.listLibRoots(getProjectDir());
    }

    private File getProjectDir() {
        try {
            Project project = Platform.getProject();
            return project == null ? null : project.getProjectDir();
        } catch (Throwable t) {
            return null;
        }
    }

    private File getLinuxTmpDir() {
        File dir = new File(EnvUtils.getEnvironmentDir(Platform.getApplicationContext(), "linux"), "tmp");
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("创建临时目录失败: " + dir);
        }
        return dir;
    }

    @Override
    public String getId() {
        return "compileWithGxx";
    }

    @Override
    public String getName() {
        return "编译到目标平台";
    }
}
