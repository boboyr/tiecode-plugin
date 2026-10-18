package com.scala.android.project;

import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.lint.Diagnostic;
import com.rapid.api.lang.engine.lint.DiagnosticSeverity;
import com.rapid.api.lang.engine.lint.LintProvider;
import com.rapid.framework.data.document.Position;
import com.rapid.framework.data.document.Range;
import com.rapid.framework.vfs.VirtualFile;
import com.scala.android.compiler.ScalaAnalysisApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scala 诊断：把编译器的错误变成编辑器里的红线/波浪线。
 *
 * <p>宿主在每次改动后调 {@code lint(virtualFile)}，再把返回的 {@code Diagnostic}
 * 交给 {@code QiCodeEditor.showDiagnostics()} 画波浪线，所以这里就是"红色波浪线"的来源。
 *
 * <p>走的是交互式驱动（typer 阶段），能覆盖绝大多数"写的时候就会错"的情况：
 * 语法错、找不到的名字/类型（未 import）、类型不匹配、成员不存在等。
 * 更深的检查（例如"类还有抽象成员没实现"）要跑完整编译，那部分放在
 * {@link ScalaAbstractMembers} + 快速修复里做。
 */
public class ScalaLintProvider implements LintProvider {

    /** 内容没变就不重复分析（宿主会在光标移动等时机反复调 lint）。 */
    private volatile String cachedContent;
    private volatile List<Diagnostic> cached = Collections.emptyList();
    /** "类还缺抽象成员"要靠完整编译，代价大，所以最多每 10 秒查一次。 */
    private volatile long lastFullCheckAt = 0L;
    private static final long FULL_CHECK_INTERVAL_MS = 10_000L;

    @Override
    public List<Diagnostic> lint(VirtualFile file, CancellationToken token) {
        if (file == null || token != null && token.isCancellationRequested) {
            return Collections.emptyList();
        }
        if (!isScalaFile(file)) {
            return Collections.emptyList();
        }
        String content;
        try {
            content = file.asString();
        } catch (Throwable error) {
            return Collections.emptyList();
        }
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        if (content.equals(cachedContent)) {
            return cached;
        }
        List<Diagnostic> diagnostics = analyze(content, file);
        cachedContent = content;
        cached = diagnostics;
        return diagnostics;
    }

    private List<Diagnostic> analyze(String content, VirtualFile file) {
        List<Diagnostic> result = new ArrayList<>();
        try {
            List<ScalaAnalysisApi.Problem> problems = ScalaAnalysisApi.diagnose(
                    content, file.asURI(), ScalaAbstractMembers.classpath());
            for (ScalaAnalysisApi.Problem problem : problems) {
                if (problem.message == null || problem.message.isEmpty()) {
                    continue;
                }
                Diagnostic diagnostic = new Diagnostic();
                diagnostic.range = range(content, problem.start, problem.end);
                diagnostic.message = problem.message;
                diagnostic.severity = problem.level >= 2
                        ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
                diagnostic.source = "scala";
                // 机器可读的标记：快速修复据此判断"这是不是我认识的错误"。
                diagnostic.code = code(problem.message);
                result.add(diagnostic);
            }
        } catch (Throwable ignored) {
            // 分析失败只是没有波浪线，不能影响编辑。
        }
        addMissingMembers(result, content);
        return result;
    }

    /**
     * 补一条"类还有 N 个抽象成员没实现"的诊断（画在类名上）。
     *
     * <p>这条错是编译器<b>后置阶段</b>（refchecks）报的，交互式驱动不会跑到那一步，
     * 只能整份编译一次；所以这里节流：最多每 {@link #FULL_CHECK_INTERVAL_MS} 毫秒查一次，
     * 结果按内容 hash 缓存，连续打字不会反复编译。
     */
    private void addMissingMembers(List<Diagnostic> result, String content) {
        long now = System.currentTimeMillis();
        if (now - lastFullCheckAt < FULL_CHECK_INTERVAL_MS) {
            return;
        }
        lastFullCheckAt = now;
        try {
            ScalaAbstractMembers.Missing missing = ScalaAbstractMembers.missing(
                    content, ScalaAbstractMembers.classpath());
            if (missing == null || missing.className.isEmpty()) {
                return;
            }
            int nameStart = content.indexOf(missing.className);
            if (nameStart < 0) {
                return;
            }
            Diagnostic diagnostic = new Diagnostic();
            diagnostic.range = range(content, nameStart, nameStart + missing.className.length());
            diagnostic.message = "类 " + missing.className + " 还有 " + missing.stubs.size()
                    + " 个抽象成员没实现（菜单「生成代码」里可以一次补全）";
            diagnostic.severity = DiagnosticSeverity.ERROR;
            diagnostic.source = "scala";
            diagnostic.code = "missingMembers";
            result.add(diagnostic);
        } catch (Throwable ignored) {
            // 补不上就算了，不能影响其它诊断。
        }
    }

    /**
     * 把一个错误消息归类，快速修复用：
     * {@code notFound}（找不到名字）、{@code syntax}（语法错）、{@code other}。
     */
    static String code(String message) {
        if (message == null) {
            return "other";
        }
        if (message.startsWith("Not found:")) {
            return "notFound";
        }
        if (message.contains("expected but") || message.contains("Illegal start")
                || message.contains("end of toplevel definition expected")) {
            return "syntax";
        }
        return "other";
    }

    /** 偏移量 → 文档 Range（行/列从内容里数，index 直接给编辑器用）。 */
    static Range range(String content, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, content.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, content.length()));
        return new Range(positionOf(content, safeStart), positionOf(content, safeEnd));
    }

    static Position positionOf(String content, int offset) {
        int line = 0;
        int lineStart = 0;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, limit - lineStart, limit);
    }

    private static boolean isScalaFile(VirtualFile file) {
        String name = null;
        try {
            name = file.getName();
        } catch (Throwable ignored) {
            return false;
        }
        if (name == null) {
            return false;
        }
        return name.endsWith(".scala") || name.endsWith(".sc") || name.endsWith(".sbt");
    }
}
