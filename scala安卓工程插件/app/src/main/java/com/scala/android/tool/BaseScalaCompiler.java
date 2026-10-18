package com.scala.android.tool;

import android.content.Context;

import com.rapid.api.project.toolchain.Tool;
import com.scala.android.compiler.ScalaCompilerApi;

import java.io.File;

import dotty.tools.dotc.interfaces.Diagnostic;

/**
 * 进程内的 Scala 3 编译器实现。
 *
 * <p>编译逻辑完全复用 {@code scala-android-compiler} 工程里封装好的
 * {@link ScalaCompilerApi}（dotty 的 {@code Main.process}），本类只负责：
 * <ul>
 *   <li>把宿主给的回调变成一个会打印诊断的 {@link Tool.OnExecutionCallback}</li>
 *   <li>把诊断的等级/位置格式化成编译器风格的文本</li>
 * </ul>
 */
public class BaseScalaCompiler implements ScalaCompile {

    @Override
    public int compile(ScalaCompileParams params, Tool.OnExecutionCallback callback) {
        if (params == null || params.sourceFiles == null || params.sourceFiles.isEmpty()) {
            print(callback, Tool.LogLevel.ERROR, "没有可编译的 Scala 源文件");
            return -1;
        }
        if (params.outputDirectory == null) {
            print(callback, Tool.LogLevel.ERROR, "未指定 class 输出目录");
            return -1;
        }

        print(callback, Tool.LogLevel.INFO, "scalac " + ScalaCompileApiKeys.VERSION
                + " · 源文件 " + params.sourceFiles.size() + " 个"
                + " · 输出 " + params.outputDirectory.getAbsolutePath());
        long begin = System.currentTimeMillis();
        try {
            ScalaCompilerApi.Result result = ScalaCompilerApi.compile(
                    params.sourceFiles, params.outputDirectory, params.classpath);
            for (ScalaCompilerApi.DiagnosticInfo diagnostic : result.diagnostics) {
                print(callback, toLevel(diagnostic.level), format(diagnostic));
            }
            long elapsed = System.currentTimeMillis() - begin;
            if (result.isSuccess()) {
                print(callback, Tool.LogLevel.INFO, "编译完成 · 错误 0 · 警告 " + result.warningCount
                        + " · 耗时 " + elapsed + "ms");
                return 0;
            }
            print(callback, Tool.LogLevel.ERROR, "编译失败 · 错误 " + result.errorCount
                    + " · 警告 " + result.warningCount + " · 耗时 " + elapsed + "ms");
            return 1;
        } catch (Throwable error) {
            print(callback, Tool.LogLevel.ERROR, "Scala 编译器异常: " + describe(error));
            return -1;
        }
    }

    @Override
    public boolean isInstalled(Context context) {
        // 编译器（scala3-compiler_3 等 jar）作为 implementation 依赖打进了插件，
        // 插件能加载到本类就说明编译器可用。
        return true;
    }

    @Override
    public void install(Context context) {
        // 无需安装动作。
    }

    private static Tool.LogLevel toLevel(int level) {
        if (level == Diagnostic.ERROR) {
            return Tool.LogLevel.ERROR;
        }
        if (level == Diagnostic.WARNING) {
            return Tool.LogLevel.WARNING;
        }
        return Tool.LogLevel.INFO;
    }

    /** 输出成 javac/scalac 常见的 {@code 文件:行:列 消息} 形式。 */
    private static String format(ScalaCompilerApi.DiagnosticInfo diagnostic) {
        String name = diagnostic.sourcePath == null || diagnostic.sourcePath.isEmpty()
                ? "" : new File(diagnostic.sourcePath).getName();
        if (diagnostic.line > 0) {
            return name + ":" + diagnostic.line + ":" + diagnostic.column + " " + diagnostic.message;
        }
        return diagnostic.message == null ? "" : diagnostic.message;
    }

    private static void print(Tool.OnExecutionCallback callback, Tool.LogLevel level, String text) {
        if (callback != null && text != null) {
            callback.onPrintLine(level, text);
        }
    }

    private static String describe(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (result.length() > 0) {
                result.append("\nCaused by: ");
            }
            result.append(current.getClass().getName());
            if (current.getMessage() != null) {
                result.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return result.toString();
    }
}
