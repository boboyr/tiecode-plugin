package com.tiecode.ktcplugin;
import com.rapid.api.project.toolchain.android.d8.D8Compiler;
import com.rapid.api.project.toolchain.android.d8.D8CompileParams;
import com.rapid.api.project.toolchain.Tool.OnExecutionCallback;
import com.rapid.api.project.toolchain.Tool;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;


/**
 * 插件自带的 D8 编译器实现（把 {@link Tool#D8} 换成插件里这份新版 r8）。
 *
 * <h3>为什么字段要用反射读</h3>
 * 结绳的 {@code D8CompileParams} 是<b>宿主运行期</b>提供的类型，本插件只能
 * compileOnly 拿到某个版本的编译期签名，运行时用的却是用户装的那版结绳。
 * 一旦宿主版本变化删掉某个字段（实际发生过：{@code proguardInputMapFile}
 * 在较新版本里已被移除），直接读字段就会抛
 * <pre>
 * NoSuchFieldError: No field proguardInputMapFile of type Ljava/nio/file/Path;
 *   in class Lcom/rapid/api/project/toolchain/android/d8/D8CompileParams;
 * </pre>
 * 表现为「构建走到『编译JVM字节码到DEX』这一步整体失败」，而且堆栈指向插件，
 * 看起来像插件自己的 bug。
 *
 * <p>所以除 {@code programFiles} 之外的可选字段都走反射：读不到就当没设置，
 * 同一份实现能同时跑在删字段前后的结绳版本上。
 */
public class D8CompilerImpl implements D8Compiler {

    /** 旧式 proguard 输入映射表；宿主新版本已从 D8CompileParams 移除。 */
    private static final String FIELD_PROGUARD_INPUT_MAP = "proguardInputMapFile";

    @Override
    public boolean compile(D8CompileParams d8CompileParams, Tool.OnExecutionCallback onExecutionCallback) {
        try {
            if (d8CompileParams == null) {
                report(onExecutionCallback, Tool.LogLevel.ERROR, "D8 编译参数为空");
                return false;
            }
            D8Command.Builder builder = D8Command.builder(new a(this, onExecutionCallback));
            builder.addProgramFiles(d8CompileParams.programFiles);
            addFiles(builder, "libraryFiles", d8CompileParams);
            addFiles(builder, "classpathFiles", d8CompileParams);
            builder.setMode(readBoolean(d8CompileParams, "debug", false)
                    ? CompilationMode.DEBUG : CompilationMode.RELEASE);
            builder.setMinApiLevel(readInt(d8CompileParams, "minApiLevel", 21));
            builder.setOutput((Path) readField(d8CompileParams, "outputDir"), outputModeOf(d8CompileParams));
            applyProguardInputMap(builder, d8CompileParams);

            com.android.tools.r8.D8.run(builder.build());
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            report(onExecutionCallback, Tool.LogLevel.ERROR, e.getMessage());
            return false;
        }
    }

    /**
     * 把宿主的 {@code OutputKind} 映射到 r8 的 {@link OutputMode}。
     *
     * <p>按<b>枚举名</b>比较而不是引用枚举常量：直接引用的话，宿主一旦改动这个
     * 枚举，错误会发生在类加载/字段解析阶段，比读到 null 更早、更难定位。
     */
    private static OutputMode outputModeOf(D8CompileParams params) {
        Object outputKind = readField(params, "outputKind");
        String name = outputKind == null ? null : outputKind.toString();
        if ("DexFilePerClass".equals(name)) {
            return OutputMode.DexFilePerClass;
        }
        if ("DexFilePerClassFile".equals(name)) {
            return OutputMode.DexFilePerClassFile;
        }
        if ("ClassFile".equals(name)) {
            return OutputMode.ClassFile;
        }
        // DexIndexed 与未知值都按 DexIndexed 输出，总比整个构建失败好。
        return OutputMode.DexIndexed;
    }

    /** 反射读取一个 "Collection&lt;Path&gt;" 字段并加到 builder；字段缺失时静默跳过。 */
    @SuppressWarnings("unchecked")
    private static void addFiles(D8Command.Builder builder, String fieldName, D8CompileParams params) {
        Object value = readField(params, fieldName);
        if (!(value instanceof Collection)) {
            return;
        }
        Collection<Path> files = (Collection<Path>) value;
        if (files.isEmpty()) {
            return;
        }
        if ("libraryFiles".equals(fieldName)) {
            builder.addLibraryFiles(files);
        } else {
            builder.addClasspathFiles(files);
        }
    }

    /**
     * 可选地设置 proguard 输入映射表；宿主没有这个字段时静默跳过。
     */
    private static void applyProguardInputMap(D8Command.Builder builder, D8CompileParams params) {
        Object value = readField(params, FIELD_PROGUARD_INPUT_MAP);
        if (value instanceof Path) {
            builder.setProguardInputMapFile((Path) value);
        }
    }

    /**
     * 反射读取宿主 {@code D8CompileParams} 的字段。
     *
     * @return 字段值；字段不存在、不可访问或类型不符时返回 {@code null}
     */
    private static Object readField(D8CompileParams params, String name) {
        try {
            Field field = params.getClass().getField(name);
            return field.get(params);
        } catch (NoSuchFieldException ignored) {
            // 宿主该版本没有这个字段——正是要兼容的情况。
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean readBoolean(D8CompileParams params, String name, boolean fallback) {
        Object value = readField(params, name);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int readInt(D8CompileParams params, String name, int fallback) {
        Object value = readField(params, name);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    private static void report(Tool.OnExecutionCallback callback, Tool.LogLevel level, String message) {
        if (callback != null) {
            callback.onPrintLine(level, message);
        }
    }



    public static class a implements DiagnosticsHandler {
        final Tool.OnExecutionCallback a;
        final D8CompilerImpl b;

        a(D8CompilerImpl aVar, Tool.OnExecutionCallback onExecutionCallback) {
            this.b = aVar;
            this.a = onExecutionCallback;
        }

        public void error(Diagnostic diagnostic) {
            Tool.OnExecutionCallback onExecutionCallback = this.a;
            if (onExecutionCallback != null) {
                onExecutionCallback.onPrintLine(Tool.LogLevel.ERROR, diagnostic.getDiagnosticMessage());
            }
        }

        public void warning(Diagnostic diagnostic) {
            Tool.OnExecutionCallback onExecutionCallback = this.a;
            if (onExecutionCallback != null) {
                onExecutionCallback.onPrintLine(Tool.LogLevel.WARNING, diagnostic.getDiagnosticMessage());
            }
        }
    }
}
