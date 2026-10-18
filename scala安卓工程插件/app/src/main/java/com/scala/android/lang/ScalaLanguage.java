package com.scala.android.lang;

import com.scala.android.lang.highlight.ScalaHighlightAnalyzer;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

/**
 * Scala 语言：接入语法高亮、自动缩进、符号配对。
 *
 * <p>必须继承 Sora 的 {@link Language}（编辑器 {@code setLanguage} 会做类型转换），
 * 并且<b>不要</b>在这里重写 {@code requireAutoComplete}：宿主
 * {@code Language.requireAutoComplete} 会把补全请求转给注册在
 * {@code LanguageManager} 里的 {@link com.scala.android.project.ScalaLanguageEngine}。
 */
public class ScalaLanguage extends Language {

    public final static String ID = "scala";

    private static final NewlineHandler[] NO_NEWLINE_HANDLERS = new NewlineHandler[0];
    private static final int INDENT_SIZE = 2;

    private final ScalaHighlightAnalyzer analyzer = new ScalaHighlightAnalyzer();

    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzer;
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_STRONG;
    }

    @Override
    public boolean isCompletionChar(char ch) {
        return ch == '.' || ch == '@' || super.isCompletionChar(ch);
    }

    /**
     * 上一行以 {@code { ( [ = => then do match yield} 结尾时多缩进一级。
     * Scala 3 的缩进语法大量依赖这个行为。
     *
     * <p>注意 {@code line} 参数是"光标所在行（0 开始），缩进会作用到 line+1"，
     * 所以要看的正是 {@code line} 这一行本身。
     */
    @Override
    public int getIndentAdvance(ContentReference content, int line, int column) {
        try {
            if (content == null) {
                return 0;
            }
            String current = content.getLine(line);
            if (current == null) {
                return 0;
            }
            String trimmed = current.trim();
            if (trimmed.isEmpty()) {
                return 0;
            }
            if (trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[")
                    || trimmed.endsWith("=") || trimmed.endsWith("=>")
                    || trimmed.endsWith("then") || trimmed.endsWith("do")
                    || trimmed.endsWith("match") || trimmed.endsWith("yield")
                    || trimmed.endsWith("else") || trimmed.endsWith("try")
                    || trimmed.endsWith("catch") || trimmed.endsWith("finally")) {
                return INDENT_SIZE;
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public boolean useTab() {
        return false;
    }

    @Override
    public Formatter getFormatter() {
        return EmptyLanguage.EmptyFormatter.INSTANCE;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new ScalaSymbolPairs();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return NO_NEWLINE_HANDLERS;
    }

    @Override
    public void destroy() {
        try {
            analyzer.destroy();
        } catch (Throwable ignored) {
            // 销毁路径上不允许抛异常。
        }
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "scala语言";
    }

    /** 括号/引号自动配对。 */
    private static final class ScalaSymbolPairs extends SymbolPairMatch {
        ScalaSymbolPairs() {
            super.putPair('{', new SymbolPair("{", "}"));
            super.putPair('[', new SymbolPair("[", "]"));
            super.putPair('(', new SymbolPair("(", ")"));
            super.putPair('"', new SymbolPair("\"", "\""));
        }
    }
}
