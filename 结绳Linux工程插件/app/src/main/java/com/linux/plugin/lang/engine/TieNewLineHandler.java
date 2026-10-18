package com.linux.plugin.lang.engine;

import cn.tiecode.compiler.TCIDEService;
import cn.tiecode.compiler.foundation.TCPosition;

import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;

/**
 * 结绳换行处理器:在块起始行(类/方法/事件/属性/如果/假如/循环)末尾回车时,
 * 由编译器计算结束关键字并自动补全缩进。
 */
public class TieNewLineHandler implements NewlineHandler {

    private String closeText;

    @Override
    public boolean matchesRequirement(Content text, CharPosition position, Styles style) {
        closeText = null;
        if (text.getColumnCount(position.line) != position.column) {
            return false;
        }
        String line = text.getLine(position.line).toString().trim();
        if (!isBlockStartLine(line)) {
            return false;
        }
        try {
            closeText = TCIDEService.newLine(text.toString(), new TCPosition(position.line, position.column));
        } catch (Throwable t) {
            closeText = null;
        }
        return closeText != null && !closeText.isEmpty();
    }

    @Override
    public NewlineHandleResult handleNewline(Content text, CharPosition position, Styles style, int tabSize) {
        if (closeText == null) {
            return null;
        }
        String text0 = closeText;
        closeText = null;
        String indent = getIndent(text0);
        String resultText = "\n" + indent + "\t" + "\n" + text0;
        return new NewlineHandleResult(resultText, text0.length() + 1);
    }

    private static boolean isBlockStartLine(String line) {
        return line.startsWith("类")
                || line.startsWith("方法")
                || line.startsWith("事件")
                || line.startsWith("属性读")
                || line.startsWith("属性写")
                || line.startsWith("如果")
                || line.startsWith("假如")
                || line.startsWith("循环");
    }

    private static String getIndent(String text) {
        int end = 0;
        while (end < text.length() && text.charAt(end) == '\t') {
            end++;
        }
        return text.substring(0, end);
    }
}
