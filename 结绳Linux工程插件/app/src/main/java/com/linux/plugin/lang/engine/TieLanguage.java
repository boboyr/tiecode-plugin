package com.linux.plugin.lang.engine;

import com.linux.plugin.lang.engine.highlight.TieHighlightAnalyzer;

import cn.tiecode.compiler.TCIDEService;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

/**
 * 结绳语言:接入语法高亮、格式化、自动缩进与符号配对。
 *
 * <p>必须继承 Sora 的 {@link Language},否则编辑器 {@code QiCodeEditor.setLanguage}
 * 会因类型转换失败而崩溃。
 */
public class TieLanguage extends Language {

    public final static String ID = "tie";

    private final TieHighlightAnalyzer analyzer = new TieHighlightAnalyzer();
    private final NewlineHandler[] newLineHandlers = new NewlineHandler[]{new TieNewLineHandler()};
    private final TiecodeFormatter formatter = new TiecodeFormatter();

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
        return ch == '@' || ch == ':' || ch == '.' || super.isCompletionChar(ch);
    }

    @Override
    public int getIndentAdvance(ContentReference content, int line, int column) {
        try {
            return TCIDEService.indentAdvance(content.getLine(line), column);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @Override
    public Formatter getFormatter() {
        return formatter;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new TieSymbolPairMatch();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newLineHandlers;
    }

    @Override
    public void destroy() {
        analyzer.destroy();
    }

    @Override
    public String getId() {
        return ID;
    }

    static final class TieSymbolPairMatch extends SymbolPairMatch {
        TieSymbolPairMatch() {
            super.putPair('{', new SymbolPair("{", "}"));
            super.putPair('[', new SymbolPair("[", "]"));
            super.putPair('(', new SymbolPair("(", ")"));
            super.putPair('"', new SymbolPair("\"", "\""));
            super.putPair('\'', new SymbolPair("'", "'"));
        }
    }
}
