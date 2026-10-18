package com.goproject.plugin.task;

import android.content.Context;

import com.goproject.plugin.tool.ToolchainEnv;
import com.goproject.plugin.tool.ToolchainManager;
import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.util.file.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用 Go 把工程编译成 Android 原生库 {@code libmain.so}。
 *
 * <p>关键点（每一条都是踩过的坑）：
 * <ol>
 *   <li><b>必须 {@code -buildmode=c-shared}</b>：只有共享库才能被
 *       {@code SDLPluginEntry} 的 {@code System.load()} + {@code dlsym()} 加载。
 *       Go 对 c-shared 强制要求外部(CGO)链接，所以必须提供 Android 的 C 编译器；
 *       这也是本插件比「Go 控制台插件」多下载一套 GCC 的原因。</li>
 *   <li><b>{@code CGO_ENABLED=1}</b>：purego 在 Android 上走 {@code internal/cgo}
 *       调 bionic 的 dlopen/dlsym（Android 的 linker namespace 决定了必须调系统实现），
 *       所以没有 cgo 就编不出。</li>
 *   <li><b>输出到私有目录</b>：{@code <env>/linux/tmp/libmain.so}。
 *       外置存储禁止 dlopen（会报 {@code is not accessible for the namespace}）。</li>
 *   <li><b>{@code GOTOOLCHAIN=local}</b>：不设的话 go 会尝试联网下载别的工具链版本。</li>
 *   <li><b>{@code GOFLAGS=-mod=mod} + {@code GOPROXY=off}</b>：purego 用
 *       {@code replace} 指向工程内的 {@code third_party/purego}，编译全程不需要联网。</li>
 * </ol>
 */
public class BuildLibmainTask extends BaseTask {

    public static final String ID = "buildLibmain";
    public static final String NAME = "编译 Go 到目标平台";

    /** 最终产物名，必须叫 libmain.so（SDLPluginEntry 的约定）。 */
    private static final String OUTPUT_NAME = "libmain.so";

    @Override
    public void prepare(Project project, ProjectVariant variant, Logger mlogger) {
        // 必须调用父类：它负责初始化 task 上下文，否则 getIntermediatePath() 返回 null
        super.prepare(project, variant, mlogger);
    }

    @Override
    public void execute() throws TaskInterruptError {
        Context appCtx = AndroidPlugins.getContext(this);
        File projectDir = Platform.getProject().getProjectDir();
        File tmpDir = ToolchainManager.tmpDir(appCtx);
        ToolchainManager.ensureDir(tmpDir);

        // ---- 1. 准备两套工具链 ----
        File go;
        File gcc;
        File gxx;
        try {
            go = ToolchainManager.ensureGo(appCtx, logger);
            gcc = ToolchainManager.ensureGcc(appCtx, logger);
            gxx = ToolchainManager.gxxBinary(appCtx);
        } catch (IOException e) {
            logger.trace(e);
            throw new TaskInterruptError("工具链安装失败: " + e.getMessage());
        }

        File logDir = new File(projectDir, "output");
        ToolchainManager.ensureDir(logDir);

        // ---- 2. 兼容补丁：让 go-sdl3 容忍老版 libSDL3.so 缺符号 ----
        patchSdl3Tolerance(projectDir, logger);

        File out = new File(tmpDir, OUTPUT_NAME);
        if (out.exists()) {
            // 编译前删除旧产物，避免「上次遗留的文件」冒充本次编译成功
            boolean deleted = out.delete();
            logger.info("删除旧产物: " + out + " -> " + deleted);
        }

        // ---- 3. 拼命令行 ----
        List<String> cmd = new ArrayList<>();
        cmd.add(go.getAbsolutePath());
        cmd.add("build");
        cmd.add("-buildmode=c-shared");
        cmd.add("-trimpath");
        cmd.add("-ldflags=-s -w -extldflags=-Wl,-soname,libmain.so");
        cmd.add("-o");
        cmd.add(out.getAbsolutePath());
        cmd.add(".");

        logger.info("工程目录: " + projectDir.getAbsolutePath());
        logger.info("Go 命令: " + join(cmd));

        // ---- 4. 环境变量 ----
        Map<String, String> env = ToolchainEnv.build(appCtx, go, gcc, gxx);

        // ---- 5. 执行 ----
        int code = run(cmd, projectDir, env, logger);

        if (code != 0) {
            throw new TaskInterruptError("Go 编译失败（返回码 " + code + "），详见日志");
        }
        if (!out.exists() || out.length() == 0) {
            throw new TaskInterruptError("Go 编译返回 0 但没有产物: " + out);
        }
        logger.info("编译完成: " + out + "（" + out.length() + " 字节）");

        // ---- 6. 同步一份到任务中间目录，便于在 build/intermediates 下查看 ----
        try {
            File intermediate = new File(getIntermediatePath());
            ToolchainManager.ensureDir(intermediate);
            FileUtils.copyFile(out.getAbsolutePath(), new File(intermediate, OUTPUT_NAME).getAbsolutePath());
            logger.info("已同步到中间目录: " + getIntermediatePath());
        } catch (IOException e) {
            // 同步失败不影响运行（真正的产物已经在 tmp 里了）
            logger.info("同步中间目录失败（忽略）: " + e);
        }
    }

    /**
     * go-sdl3 的 SDL3 版本适配补丁。
     *
     * <p>本插件 assets 里的 {@code libSDL3.so} 是 <b>SDL3 3.2.28</b>，
     * 而 go-sdl3 v0.1.1 是按 <b>3.4.0</b> 生成的：它的 {@code sdl.LoadLibrary()}
     * 会<b>急切解析</b>全部 987 + 43 个 {@code SDL_*} 符号，原始行为是缺一个就
     * {@code panic("cannot puregogen.OpenSymbol: ...")}。实测缺 30 个
     * （{@code SDL_LoadPNG} / {@code SDL_GetSystemPageSize} / {@code SDL_SetWindowProgressState} …），
     * 都是示例用不到的 GPU / PNG / 渲染状态接口。
     *
     * <p>这里把两处「缺符号就 panic」降级成「记录 + 留空函数指针」：
     * <ul>
     *   <li>{@code panic("cannot puregogen.OpenSymbol: X")} → {@code missingSymbol("X")}</li>
     *   <li>{@code purego.RegisterLibFunc(f, h, "X")} → {@code registerLibFuncOrSkip(f, h, "X")}
     *       （purego 的 RegisterLibFunc 内部自己会 panic，必须一起换掉）</li>
     * </ul>
     *
     * <p>方法是<b>幂等</b>的：每次构建都跑一遍，所以即使开发者重新执行
     * {@code go mod vendor} 把原始文件还原回来，构建时也会自动补回，
     * 不会出现「昨天能跑今天 panic」。
     */
    private void patchSdl3Tolerance(File projectDir, Logger logger) {
        File sdlDir = new File(projectDir, "vendor/github.com/Zyko0/go-sdl3/sdl");
        if (!sdlDir.isDirectory()) {
            logger.info("未找到 vendor 里的 go-sdl3，跳过兼容补丁: " + sdlDir);
            return;
        }

        // 1) 写入补丁配套的两个函数（每次覆盖，保证存在）
        File helper = new File(sdlDir, "zz_plugin_missing.go");
        writeText(helper, String.join("\n", HELPER_SOURCE));

        // 2) 扫描并降级所有 panic 点
        File[] files = sdlDir.listFiles();
        if (files == null) {
            return;
        }
        int patchedFiles = 0;
        int patchedSites = 0;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".go") || name.endsWith("_js.go") || name.equals(helper.getName())) {
                continue;
            }
            String src = readText(f);
            if (src == null) {
                continue;
            }
            int before = countOf(src, OPEN_SYMBOL_PANIC) + countOf(src, REGISTER_LIB_FUNC);
            if (before == 0) {
                continue;
            }
            String out = src
                    .replaceAll("panic\\(\"cannot puregogen\\.OpenSymbol: ([^\"]+)\"\\)",
                            "missingSymbol(\"$1\")")
                    .replaceAll("purego\\.RegisterLibFunc\\((&[A-Za-z0-9_]+), _hnd_sdl, \"([^\"]+)\"\\)",
                            "registerLibFuncOrSkip($1, _hnd_sdl, \"$2\")");
            int after = countOf(out, OPEN_SYMBOL_PANIC) + countOf(out, REGISTER_LIB_FUNC);
            if (after < before) {
                writeText(f, out);
                patchedFiles++;
                patchedSites += (before - after);
            }
        }
        if (patchedSites > 0) {
            logger.info("go-sdl3 兼容补丁: " + patchedSites + " 处符号解析已降级（"
                    + patchedFiles + " 个文件）");
        } else {
            logger.info("go-sdl3 兼容补丁: 已经是降级后的状态");
        }
    }

    private static final String OPEN_SYMBOL_PANIC = "cannot puregogen.OpenSymbol";
    private static final String REGISTER_LIB_FUNC = "purego.RegisterLibFunc(";

    /** zz_plugin_missing.go 的内容（构建时写入 vendor）；与 template-src/zz_plugin_missing.go 一致，无注释。 */
    private static final String[] HELPER_SOURCE = {
            "package sdl",
            "",
            "import (",
            "	\"log\"",
            "",
            "	purego \"github.com/ebitengine/purego\"",
            ")",
            "",
            "var MissingSymbols []string",
            "",
            "func missingSymbol(name string) {",
            "	MissingSymbols = append(MissingSymbols, name)",
            "	log.Printf(\"[go-sdl3] libSDL3.so 缺少符号 %s（已跳过）\", name)",
            "}",
            "",
            "func registerLibFuncOrSkip(fptr any, handle uintptr, name string) {",
            "	addr, err := purego.Dlsym(handle, name)",
            "	if err != nil || addr == 0 {",
            "		missingSymbol(name)",
            "		return",
            "	}",
            "	purego.RegisterFunc(fptr, addr)",
            "}",
    };

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static String readText(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String content) {
        try {
            // 从 assets 解出来的文件可能是只读的
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true);
            Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /**
     * 起进程执行，滚动把输出写进 IDE 的构建日志。
     *
     * @return 进程退出码
     */
    private int run(List<String> cmd, File workDir, Map<String, String> env, Logger logger) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        // ProcessBuilder 的 environment() 是增量的：父进程的环境会保留，
        // 但我们要覆盖 GOROOT/CC 等，所以逐个 put。
        pb.environment().putAll(env);

        Process process = null;
        try {
            process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info(line);
                }
            }
            return process.waitFor();
        } catch (IOException e) {
            logger.trace(e);
            logger.error("无法启动 go: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroy();
            }
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String join(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(args.get(i));
        }
        return sb.toString();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
