package com.scala.android.compiler;

import dotty.tools.dotc.CompilationUnit;
import dotty.tools.dotc.core.Contexts;
import dotty.tools.dotc.interactive.Completion;
import dotty.tools.dotc.interactive.InteractiveDriver;
import dotty.tools.dotc.util.SourceFile;
import dotty.tools.dotc.util.SourcePosition;
import dotty.tools.dotc.util.Spans;
import scala.Tuple2;
import scala.collection.immutable.List;
import scala.jdk.javaapi.CollectionConverters;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 dotty 交互式编译器的类型级补全门面（取自 scala-android-compiler）。
 *
 * <h3>与上游的唯一区别：复用 InteractiveDriver</h3>
 * 上游每次请求都 {@code new InteractiveDriver(settings)}；构造它要做编译器 setup
 * 并遍历 classpath 里的所有 zip 收集类名，单次就要几秒，放在补全路径上等于不可用。
 * 这里按 classpath 缓存一个 driver，之后只调 {@code run(uri, source)} 重新编译当前文件
 * （这正是 dotty 给 IDE 设计的用法），单次分析从"几秒"降到"几百毫秒"级。
 *
 * <p>线程约束：内部用锁串行化，调用方只应有一条后台线程使用它。
 */
public final class ScalaAnalysisApi {

    private static final Object LOCK = new Object();

    private static InteractiveDriver driver;
    private static String driverClasspathKey;

    private ScalaAnalysisApi() {
    }

    public static java.util.List<Suggestion> complete(
            String source,
            URI sourceUri,
            int offset,
            java.util.List<File> classpath
    ) {
        if (source == null || sourceUri == null) {
            return java.util.Collections.emptyList();
        }
        synchronized (LOCK) {
            InteractiveDriver active = null;
            try {
                active = driver(classpath);
                SourceFile sourceFile = SourceFile.virtual(sourceUri, source);
                active.run(sourceUri, sourceFile);
                int safeOffset = Math.max(0, Math.min(offset, source.length()));
                SourcePosition position = sourceFile.atSpan(Spans.Span(safeOffset));
                CompilationUnit unit = (CompilationUnit) active.compilationUnits().get(sourceUri).get();
                Contexts.Context context = active.currentCtx().fresh().setCompilationUnit(unit);
                Tuple2<Object, List<Completion>> response = Completion.completions(position, context);
                Map<String, Suggestion> unique = new LinkedHashMap<>();
                for (Completion completion : CollectionConverters.asJava(response._2())) {
                    String label = completion.label();
                    if (label != null && !label.isEmpty()) {
                        String description = completion.description();
                        unique.putIfAbsent(label, new Suggestion(label, description, takesParams(description)));
                    }
                }
                return new ArrayList<>(unique.values());
            } catch (Throwable ignored) {
                // 交互式分析失败（源码有语法错误、内存不足等）只是没有语义候选，
                // 不能影响普通补全。
                return java.util.Collections.emptyList();
            } finally {
                if (active != null) {
                    try {
                        active.close(sourceUri);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        }
    }

    /**
     * 取当前源码的<b>快速诊断</b>（typer 阶段）：语法错误、找不到的名字/类型、类型不匹配等。
     *
     * <p>复用同一个 InteractiveDriver，所以热起来之后是几百毫秒级，
     * 足够在"改一行就重新 lint"的节奏里用。
     *
     * <p>注意：像"类还有 N 个抽象成员没实现"这种是后置阶段（refchecks）才报的，
     * 交互式驱动不会跑到那一步 —— 那条交给 {@code ScalaAbstractMembers} 用完整编译去算。
     */
    public static java.util.List<Problem> diagnose(
            String source, URI sourceUri, java.util.List<File> classpath) {
        if (source == null || sourceUri == null) {
            return java.util.Collections.emptyList();
        }
        synchronized (LOCK) {
            InteractiveDriver active = null;
            try {
                active = driver(classpath);
                SourceFile sourceFile = SourceFile.virtual(sourceUri, source);
                active.run(sourceUri, sourceFile);
                java.util.List<Problem> problems = new java.util.ArrayList<>();
                for (dotty.tools.dotc.interfaces.Diagnostic diagnostic
                        : CollectionConverters.asJava(active.currentCtx().reporter().allErrors())) {
                    java.util.Optional<dotty.tools.dotc.interfaces.SourcePosition> position =
                            diagnostic.position();
                    if (!position.isPresent()) {
                        continue;
                    }
                    dotty.tools.dotc.interfaces.SourcePosition span = position.get();
                    String message = diagnostic.message();
                    if (message == null || message.isEmpty()) {
                        continue;
                    }
                    problems.add(new Problem(span.start(), span.end(), message, diagnostic.level()));
                }
                return problems;
            } catch (Throwable ignored) {
                return java.util.Collections.emptyList();
            } finally {
                if (active != null) {
                    try {
                        active.close(sourceUri);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        }
    }

    /** 一条诊断（偏移量 + 消息）。 */
    public static final class Problem {
        public final int start;
        public final int end;
        public final String message;
        /** dotty 的等级：2 = 错误，1 = 警告。 */
        public final int level;

        private Problem(int start, int end, String message, int level) {
            this.start = start;
            this.end = end;
            this.message = message;
            this.level = level;
        }
    }

    /**
     * 这个 classpath 的 driver 是否已经建好。
     *
     * <p>建 driver 要遍历整个 classpath 收集类名，冷启动一次要几秒。补全线程不能等，
     * 所以调用方先用本方法判断：冷的就后台 {@link #warmUp} 预热，本次补全先不给语义候选。
     */
    public static boolean isWarm(java.util.List<File> classpath) {
        String key = joinClasspath(classpath);
        synchronized (LOCK) {
            return driver != null && key.equals(driverClasspathKey);
        }
    }

    /** 后台预热：把 driver 建出来（建好后 {@link #isWarm} 就为 true）。 */
    public static void warmUp(java.util.List<File> classpath) {
        synchronized (LOCK) {
            try {
                driver(classpath);
            } catch (Throwable ignored) {
                // 预热失败不影响功能，下次再试。
            }
        }
    }

    private static InteractiveDriver driver(java.util.List<File> classpath) {
        String key = joinClasspath(classpath);
        if (driver != null && key.equals(driverClasspathKey)) {
            return driver;
        }
        java.util.List<String> arguments = new ArrayList<>();
        arguments.add("-color:never");
        if (!key.isEmpty()) {
            arguments.add("-classpath");
            arguments.add(key);
        }
        List<String> settings = List.from(CollectionConverters.asScala(arguments));
        driver = new InteractiveDriver(settings);
        driverClasspathKey = key;
        return driver;
    }

    private static String joinClasspath(java.util.List<File> files) {
        if (files == null) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (value.length() > 0) {
                value.append(File.pathSeparatorChar);
            }
            value.append(file.getAbsolutePath());
        }
        return value.toString();
    }

    /**
     * 判断一个候选是否<b>需要显式参数列表</b>——决定补全后要不要自动加 {@code ()}。
     *
     * <p>dotty 给出的描述格式是
     * {@code [类型参数](参数列表...): 结果类型} / {@code : 结果类型} / {@code => 结果类型}，
     * 所以只看"结果类型之前"的部分：
     * <ul>
     *   <li>以 {@code (} 开头 → 有参数列表；</li>
     *   <li>以 {@code [} 开头 → 跳过配对的中括号，后面紧跟 {@code (} 才算；</li>
     *   <li>以 {@code :} / {@code =>} / 其它开头（val、无参方法、类型）→ 不需要参数；</li>
     *   <li>参数列表是 {@code implicit}/{@code using} 的 → 调用时不用写，也不需要 {@code ()}。</li>
     * </ul>
     */
    static boolean takesParams(String description) {
        if (description == null) {
            return false;
        }
        String text = description.trim();
        if (text.isEmpty()) {
            return false;
        }
        int index = 0;
        if (text.charAt(0) == '[') {
            int close = matchingBracket(text, 0);
            if (close < 0) {
                return false;
            }
            index = close + 1;
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index >= text.length() || text.charAt(index) != '(') {
                return false;
            }
        } else if (text.charAt(0) != '(') {
            return false;
        }
        String rest = text.substring(index + 1).trim();
        return !(rest.startsWith("implicit ") || rest.startsWith("using ")
                || rest.startsWith("/*") || rest.startsWith("*/"));
    }

    private static int matchingBracket(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static final class Suggestion {
        public final String label;
        public final String detail;
        /** 需要显式参数列表（补全会自动加 {@code ()}）。 */
        public final boolean takesParams;

        private Suggestion(String label, String detail, boolean takesParams) {
            this.label = label;
            this.detail = detail;
            this.takesParams = takesParams;
        }
    }
}
