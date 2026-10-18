package com.linux.plugin.task;

import com.linux.plugin.lang.engine.TiecodeLanguageContext;

import com.rapid.api.Platform;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cn.tiecode.compiler.TCCompiler;
import cn.tiecode.compiler.TCContext;
import cn.tiecode.compiler.component.TCDiagnosticHandler;
import cn.tiecode.compiler.component.TCOptions;
import cn.tiecode.compiler.component.TCTargetPlatform;
import cn.tiecode.compiler.foundation.TCDiagnostic;
import cn.tiecode.compiler.foundation.TCLogLevel;
import cn.tiecode.compiler.foundation.TCSourceVersion;
import android.content.Context;
import com.rapid.util.android.AssetUtils;
import com.rapid.api.ext.plugin.AndroidPlugins;

/**
 * 结绳源文件编译任务(Linux 平台)。
 *
 * <p>收集工程源代码与绳包源代码,调用结绳编译器生成 C++ 源码,
 * 输出到任务中间目录的 {@code src} 子目录,供后续 Gxx 任务继续编译。
 *
 * <p>注意:必须使用 {@code ideMode=false} 且 {@code hardMode=true},
 * IDE 模式只做语法分析不产出文件,硬输出模式会把依赖头文件拷贝到输出目录,
 * 否则后续 C++ 编译会因缺少依赖而没有输入文件。
 */
public class TieCompileTask extends BaseTask {

    public static final String ID = "compileWithTieclinux";
    public static final String NAME = "编译结绳源文件";
    public static final String LINE_MAP_NAME = "mapping.bin";

    @Override
    public void execute() throws TaskInterruptError {
        Project project = Platform.getProject();
        if (project == null) {
            throw new TaskInterruptError("未打开任何工程");
        }

        File projectDir = project.getProjectDir();
        List<File> sources = TiecodeLanguageContext.scanCompileSources(projectDir);
        if (sources.isEmpty()) {
            throw new TaskInterruptError("未找到任何结绳源文件(源代码/绳包/*/源代码 下的 .t)");
        }

        TCOptions options = new TCOptions();
        options.sourceVersion = TCSourceVersion.VERSION_4_7;
        options.platform = TCTargetPlatform.LINUX;
        options.packageName = "com.tie.compiler";
        options.debug = true;
        // 关键:非 IDE 模式才会输出产物;硬输出模式才会拷贝依赖头文件。
        options.ideMode = false;
        options.hardMode = true;
        options.logLevel = TCLogLevel.WARNING;
        options.outputDir = getIntermediatePath();
        options.lineMapPath = getIntermediatePath(LINE_MAP_NAME);
        options.searchPrefixes = buildSearchPrefixes(projectDir);

        TCContext context = new TCContext(options);
        TCCompiler compiler = new TCCompiler(context);
        compiler.setDiagnosticHandler(new TaskDiagnosticHandler());

        boolean ok;
        try {
            ok = compiler.compileFiles(sources);
        } catch (Throwable t) {
            throw new TaskInterruptError("结绳源文件编译异常: " + t);
        }
        if (!ok) {
            throw new TaskInterruptError("结绳源文件编译失败,请检查上方诊断信息");
        }
        logger.info("结绳源文件编译完成,源文件数: " + sources.size());
    }

    /** 为绳包配置依赖库、源码、资源与资产的附加搜寻路径。 */
    private Map<String, List<String>> buildSearchPrefixes(File projectDir) {
        List<String> prefixes = TiecodeLanguageContext.collectSearchPrefixes(projectDir);
        if (prefixes.isEmpty()) {
            return null;
        }
        Map<String, List<String>> result = new HashMap<>();
        result.put(TCOptions.SCOPE_LIB, new ArrayList<>(prefixes));
        result.put(TCOptions.SCOPE_SOURCE, new ArrayList<>(prefixes));
        result.put(TCOptions.SCOPE_RES, new ArrayList<>(prefixes));
        result.put(TCOptions.SCOPE_ASSETS, new ArrayList<>(prefixes));
        return result;
    }

    @Override
    public void prepare(Project project, ProjectVariant variant, Logger logger) {
        super.prepare(project, variant, logger);
		
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /** 把编译器诊断信息转发到工程日志。 */
    private final class TaskDiagnosticHandler implements TCDiagnosticHandler {
        @Override
        public void report(TCDiagnostic diagnostic) {
            if (diagnostic == null) {
                return;
            }
            String message = diagnostic.message == null ? "" : diagnostic.message;
            TCLogLevel level = diagnostic.level;
            if (level == TCLogLevel.ERROR || level == TCLogLevel.FATAL) {
                logger.error(message);
            } else if (level == TCLogLevel.WARNING) {
                logger.warning(message);
            } else {
                logger.info(message);
            }
        }
    }
}
