package com.goproject.plugin.lsp.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.lang.completion.CompletionItemKind;
import io.github.rosemoe.sora.lang.completion.snippet.CodeSnippet;
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * 一个可提交的补全项：替换插入 + 应用附加编辑（自动导包）。
 *
 * <h3>为什么照着宿主 {@code IpeSimpleCompletionItem} 写</h3>
 * 结绳宿主对 {@code LanguageEngine} 的补全项有一个官方实现
 * （{@code io.github.rosemoe.sora.lang.completion.IpeSimpleCompletionItem}），
 * 它已经是验证过的正确做法，核心是两条：
 * <ol>
 *   <li><b>主编辑用行列版 API</b>：
 *       {@code text.replace(line, column - prefixLength, line, column, text)}，
 *       而不是"行列 → 绝对下标 → replace(start, end)"。后者在行尾、多行、缩进等
 *       边界上容易算错——早期版本就是这样把 import 块写坏的；</li>
 *   <li><b>附加编辑按统一规则排序</b>：按 {@code range.start} <b>倒序</b>应用，
 *       纯插入（start==end）排最前。这样"在文件前部插 import"不会顶掉后面编辑的偏移量。</li>
 * </ol>
 * 本类复刻这两条，并额外处理 LSP 片段（{@code insertTextFormat=Snippet}）。
 *
 * <h3>主编辑长度取谁</h3>
 * 优先用服务端 {@code textEdit.range} 给出的跨度；没有时才退回调用方传入的前缀长度
 * （宿主用 {@code params.partial.length()}）。两者正常情况一致，但成员补全
 * （{@code fmt.Pr}）时服务端的范围更权威。
 */
public final class GoplsCompletionItem extends CompletionItem {

    /** 提交时插入的文本。 */
    private final String insertText;

    /** 主编辑替换范围（服务端权威区间）；为 null 时按 prefixLength 推算。 */
    private final Range mainRange;

    /** 没有 mainRange 时，替换光标左侧多少个字符。 */
    private final int prefixLength;

    /** 主编辑是否为 LSP 片段。 */
    private final boolean snippet;

    /** 附加编辑（自动导包等）。 */
    private final List<TextEdit> extraEdits;

    /**
     * 已输入前缀（如 {@code fmt.Pr}）。
     *
     * <p>用于在没有服务端范围时定位替换区间——直接在行文本里找它，
     * 从而不依赖宿主给的、可能已经过期的列号。
     */
    private final String partial;

    public GoplsCompletionItem(CharSequence label, CharSequence desc, CompletionItemKind kind,
                               String insertText, Range mainRange, int prefixLength, boolean snippet,
                               List<TextEdit> additionalTextEdits) {
        this(label, desc, kind, insertText, mainRange, prefixLength, snippet,
                additionalTextEdits, null);
    }

    public GoplsCompletionItem(CharSequence label, CharSequence desc, CompletionItemKind kind,
                               String insertText, Range mainRange, int prefixLength, boolean snippet,
                               List<TextEdit> additionalTextEdits, String partial) {
        super(label, desc);
        this.partial = partial;
        this.insertText = insertText == null ? String.valueOf(label) : insertText;
        this.mainRange = mainRange;
        this.prefixLength = Math.max(0, prefixLength);
        this.snippet = snippet;
        this.extraEdits = additionalTextEdits == null
                ? Collections.<TextEdit>emptyList()
                : new ArrayList<>(additionalTextEdits);
        if (kind != null) {
            this.kind = kind;
        }
    }

    @Override
    public void performCompletion(CodeEditor editor, Content text, CharPosition position) {
        if (position == null) {
            return;
        }
        apply(editor, text, position.line, position.column);
    }

    @Override
    public void performCompletion(CodeEditor editor, Content text, int line, int column) {
        apply(editor, text, line, column);
    }

    private void apply(CodeEditor editor, Content text, int line, int column) {
        if (text == null) {
            return;
        }

        // 提交前的取证日志：把"服务端给了什么编辑"和"我要怎么应用"一次性打出来。
        GoplsLogger.get().info("提交补全 label=[" + label + "] insert=["
                + oneLine(insertText) + "] snippet=" + snippet
                + " mainRange=" + describeRange(mainRange)
                + " prefixLength=" + prefixLength
                + " 附加编辑=" + extraEdits.size() + describeExtras()
                + " 提交前行数=" + text.getLineCount() + " 宿主给的坐标=" + line + ":" + column
                + "\n提交前文档:\n" + numberedLines(text.toString(), 12));

        // 主编辑的"权威起点"必须在改动正文<b>之前</b>算出来，后面所有位移都以它为基准。
        // 服务端给了 textEdit 就用它的 start（绝对坐标，最可靠）；
        // 否则才退回宿主坐标 + 扫描前缀。
        int mainStart = -1;
        if (mainRange != null && mainRange.getStart() != null) {
            mainStart = indexOf(text, mainRange.getStart().getLine(),
                    mainRange.getStart().getCharacter());
        }
        String lineText = safeLineString(text, line);

        text.beginBatchEdit();
        try {
            // 1) 附加编辑（自动导包）先落盘：它们通常在 import 块，位于主编辑上方，
            //    插入的换行会让主编辑整体下移，所以要拿到这份位移。
            //    位移基准是 mainStart（-1 表示未知，此时不做位移修正）。
            int[] shift = applyExtraEdits(text, mainStart, line, column);

            // 2) 定出要替换的区间：优先服务端的 textEdit.range，其次在行内扫描标识符前缀。
            int[] region = resolveReplaceRegion(text, line, column, lineText);
            int replaceStart = region[0];
            int replaceEnd = region[1];
            // 若服务端给了区间，用它的起点做位移修正（更可靠）。
            if (mainStart >= 0) {
                replaceStart = mainStart + shift[0];
                replaceEnd = mainStart + shift[0] + Math.max(0, region[1] - region[0]);
            } else {
                replaceStart = region[0] + shift[0];
                replaceEnd = region[1] + shift[0];
            }
            if (replaceStart < 0) {
                replaceStart = 0;
            }
            if (replaceEnd < replaceStart) {
                replaceEnd = replaceStart;
            }

            // 3) 主编辑：先解析片段，再删旧区间，最后插入。
            CodeSnippet parsedSnippet = null;
            if (snippet) {
                try {
                    parsedSnippet = CodeSnippetParser.parse(insertText);
                } catch (Throwable parseError) {
                    GoplsLogger.get().warning("片段解析失败，按纯文本处理: " + parseError);
                }
            }

            String plain = parsedSnippet != null ? insertText : stripSnippetSyntax(insertText);
            if (replaceEnd > replaceStart) {
                text.delete(replaceStart, replaceEnd);
            }
            text.insert(replaceStart, replaceStart, plain);
            int caret = replaceStart + plain.length();

            if (parsedSnippet != null) {
                io.github.rosemoe.sora.widget.snippet.SnippetController controller =
                        editor == null ? null : editor.getSnippetController();
                if (controller != null) {
                    // 交给片段控制器：占位符可逐个跳转编辑。
                    controller.startSnippet(replaceStart, parsedSnippet, "");
                    caret = -1;
                }
            }
            if (editor != null && caret >= 0) {
                placeCaret(editor, text, caret);
            }
            GoplsLogger.get().info("提交后文档:\n" + numberedLines(text.toString(), 12));
        } catch (Throwable t) {
            // 任何异常都不能让编辑器因补全提交而崩溃。
            GoplsLogger.get().warning("应用补全失败: " + t);
        } finally {
            text.endBatchEdit();
        }
    }

    /**
     * 定出"要被替换掉"的字符区间（绝对下标）。
     *
     * <p>为什么不能直接用宿主给的 {@code line/column}：那个坐标是<b>补全请求发出时</b>的快照，
     * 而补全列表是异步弹出的——用户很可能在列表出现前又多敲了一两个字符，
     * 此时快照的列号已经过期。直接拿它去替换，就会删错位置、光标也会落错。
     *
     * <p>所以优先级是：
     * <ol>
     *   <li>服务端的 {@code textEdit.range}——绝对坐标，最可靠；</li>
     *   <li>在快照列号左侧<b>搜索 partial 文本</b>定位——即使列号过期也能找对锚点；</li>
     *   <li>都没有才退回"光标本左侧 prefixLength 个字符"。</li>
     * </ol>
     */
    private int[] resolveReplaceRegion(Content text, int line, int column, String lineText) {
        if (mainRange != null && mainRange.getStart() != null && mainRange.getEnd() != null
                && mainRange.getStart().getLine() == mainRange.getEnd().getLine()) {
            int start = indexOf(text, mainRange.getStart().getLine(),
                    mainRange.getStart().getCharacter());
            int end = indexOf(text, mainRange.getEnd().getLine(),
                    mainRange.getEnd().getCharacter());
            if (end >= start) {
                return new int[] { start, end };
            }
        }

        // 没有服务端范围时：直接在当前行里找"已输入前缀"。
        // 注意不能用宿主给的 column——它是补全请求发出时的快照，可能已经过期。
        // 改法是在该行文本里定位 partial 的最后一次出现。
        int safeLine = indexOfLine(text, line);
        int lineStart = indexOfLineStart(text, safeLine);
        String partial = partialText();
        if (lineText != null && !partial.isEmpty()) {
            int at = lineText.lastIndexOf(partial);
            if (at >= 0) {
                int start = lineStart + at;
                int end = start + partial.length();
                if (end <= text.length()) {
                    return new int[] { start, end };
                }
            }
        }

        // 再兜底：从行尾反扫出标识符前缀（允许含 .，支持 fmt.Println 这类成员补全）。
        int lineEnd = lineStart + (lineText == null ? 0 : lineText.length());
        int scanFrom = Math.min(lineEnd, text.length());
        int start = scanFrom;
        while (start > lineStart) {
            char c = text.charAt(start - 1);
            if (c == '.' || Character.isLetterOrDigit(c) || c == '_') {
                start--;
            } else {
                break;
            }
        }
        return new int[] { start, scanFrom };
    }

    /**
     * 已输入前缀。
     *
     * <p>优先用构造时传入的 {@code prefixLength} 对应的长度无法还原文本，
     * 所以这里从服务端主编辑范围的长度推断；没有范围时退化为"不指定具体文本"，
     * 由调用方走"反扫标识符"的兜底路径。
     */
    private String partialText() {
        if (partial != null) {
            return partial;
        }
        return "";
    }

    /** 行列 → 合法行号。 */
    private static int indexOfLine(Content text, int line) {
        return Math.max(0, Math.min(line, Math.max(0, text.getLineCount() - 1)));
    }

    /** 某行行首的绝对下标。 */
    private static int indexOfLineStart(Content text, int line) {
        try {
            return text.getCharIndex(line, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 行列 → 绝对下标。
     *
     * <p>列必须钳到<b>本行内容长度</b>（不含换行符）。若直接
     * {@code getCharIndex(line, 超出长度的列)}，某些实现会落到下一行行首，
     * 于是"在行尾插入"就变成"插到下一行开头"——早期版本把 import 块写坏就是这个原因。
     */
    private static int indexOf(Content text, int line, int column) {
        int safeLine = indexOfLine(text, line);
        int safeColumn = Math.max(0, Math.min(column, text.getColumnCount(safeLine)));
        try {
            return clamp(text.getCharIndex(safeLine, safeColumn), 0, text.length());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    /** 安全取某行文本。 */
    private static String safeLineString(Content text, int line) {
        try {
            return text.getLineString(indexOfLine(text, line));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把光标放到指定<b>绝对下标</b>处，并夹到合法范围。 */
    private void placeCaret(CodeEditor editor, Content text, int index) {
        int safe = Math.max(0, Math.min(index, text.length()));
        io.github.rosemoe.sora.text.Indexer indexer = text.getIndexer();
        int caretLine = indexer.getCharLine(safe);
        int caretColumn = indexer.getCharColumn(safe);
        try {
            editor.setSelection(caretLine, caretColumn);
            editor.updateCursor();
            io.github.rosemoe.sora.text.Cursor cursor = editor.getCursor();
            GoplsLogger.get().info("光标设置: 下标=" + safe
                    + " 目标=" + caretLine + ":" + caretColumn
                    + " 实际=" + cursor.getLeftLine() + ":" + cursor.getLeftColumn());
        } catch (Throwable t) {
            GoplsLogger.get().warning("设置光标失败: " + t);
        }
    }


    /**
     * 应用附加编辑。
     *
     * <p>排序规则与宿主 {@code TextEditUtils.performTextEdits} 一致：按起点<b>倒序</b>；
     * 纯插入（{@code start == end}）排在前面。这样从文件末尾往回改，
     * 前面编辑的坐标始终有效。
     *
     * <p>编辑一律用行列版 {@code text.replace(...)}——那是宿主验证过的路径，
     * 不依赖我们自己把行列换算成绝对下标。
     */
    private int[] applyExtraEdits(Content text, int mainStart, int line, int column) {
        if (extraEdits.isEmpty()) {
            return new int[] { 0, 0 };
        }
        List<TextEdit> sorted = new ArrayList<>(extraEdits.size());
        for (TextEdit edit : extraEdits) {
            if (edit == null || edit.getRange() == null
                    || edit.getRange().getStart() == null || edit.getRange().getEnd() == null) {
                continue;
            }
            sorted.add(edit);
        }
        if (sorted.isEmpty()) {
            return new int[] { 0, 0 };
        }

        Collections.sort(sorted, new Comparator<TextEdit>() {
            @Override
            public int compare(TextEdit left, TextEdit right) {
                if (left.getRange().getStart().equals(left.getRange().getEnd())) {
                    // 纯插入优先（与宿主实现一致）。
                    return 1;
                }
                int leftLine = left.getRange().getStart().getLine();
                int rightLine = right.getRange().getStart().getLine();
                if (leftLine != rightLine) {
                    return rightLine - leftLine;
                }
                return right.getRange().getStart().getCharacter()
                        - left.getRange().getStart().getCharacter();
            }
        });

        int lineShift = 0;
        int columnShift = 0;
        for (TextEdit edit : sorted) {
            int startLine = Math.max(0, edit.getRange().getStart().getLine());
            int startCharacter = Math.max(0, edit.getRange().getStart().getCharacter());
            int endLine = Math.max(0, edit.getRange().getEnd().getLine());
            int endCharacter = Math.max(0, edit.getRange().getEnd().getCharacter());
            String newText = edit.getNewText() == null ? "" : edit.getNewText();

            // 关键顺序：被替换掉的原文必须在 replace 之前读出来，
            // 否则拿到的是新文本，换行差就算反了。
            // 纯插入（起点==终点）不涉及删除，可以跳过这次读取。
            int removedNewlines = 0;
            boolean pureInsert = startLine == endLine && startCharacter == endCharacter;
            if (!pureInsert) {
                removedNewlines = countNewlinesInRange(text, startLine, startCharacter,
                        endLine, endCharacter);
            }
            int addedNewlines = countNewlines(newText);
            int delta = addedNewlines - removedNewlines;

            try {
                text.replace(startLine, startCharacter, endLine, endCharacter, newText);
            } catch (Throwable t) {
                // 单条编辑越界不应连累整次补全。
                GoplsLogger.get().warning("附加编辑应用失败(" + edit.getRange() + "): " + t);
                continue;
            }

            // 只统计完全落（或结束）在主编辑位置之前的编辑——它们才会移动主编辑。
            //
            // 基准用 mainStart（服务端给的权威起点）换算出的<b>改动前</b>下标；
            // 缺失时才退回宿主给的 (line, column)。
            if (mainStart >= 0) {
                int editStartIndex = indexOf(text, startLine, startCharacter);
                int editEndIndex = indexOf(text, endLine, endCharacter);
                if (editEndIndex <= mainStart) {
                    lineShift += delta;
                    if (editStartIndex == editEndIndex) {
                        // 纯插入且在主编辑之前：字符位置整体右移，行列都会变。
                        columnShift += newText.length();
                    }
                }
            } else if (endLine < line) {
                // 整块在上方：行号下移，列不受影响。
                lineShift += delta;
            } else if (endLine == line && endCharacter <= column) {
                // 与主编辑同一行且在它左边：只改列。
                columnShift += newText.length() - Math.max(0, endCharacter - startCharacter);
            }
        }
        if (lineShift != 0 || columnShift != 0) {
            // 这条日志是排查"插入后光标跑到别处"的关键：行位移非 0 就说明
            // 附加编辑在光标上方加了行（gopls 的自动导包正是如此）。
            GoplsLogger.get().info("附加编辑造成的位移: 行+" + lineShift + " 列+" + columnShift);
        }
        return new int[] { lineShift, columnShift };
    }

    /** 统计换行符个数。 */
    private static int countNewlines(String value) {
        if (value == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计<b>被替换掉的原文</b>里有几个换行。
     *
     * <p>{@code text.replace(...)} 之后原文已经取不到了，所以这里在调用前先取。
     */
    private static int countNewlinesInRange(Content text, int startLine, int startCharacter,
                                            int endLine, int endCharacter) {
        try {
            int start = text.getCharIndex(startLine, startCharacter);
            int end = text.getCharIndex(endLine, endCharacter);
            if (end <= start) {
                return 0;
            }
            CharSequence removed = text.subSequence(start, end);
            return countNewlines(removed.toString());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 片段语法 → 纯文本（片段控制器不可用时的兜底）。
     *
     * <p>{@code ${1:默认值}} 取默认值，{@code ${1}} / {@code $1} 丢弃：
     * 绝不把占位符原样写进代码。
     */
    private static String stripSnippetSyntax(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                sb.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int close = text.indexOf('}', i + 2);
                if (close > 0) {
                    int colon = text.indexOf(':', i + 2);
                    if (colon > 0 && colon < close) {
                        sb.append(text, colon + 1, close);
                    }
                    i = close + 1;
                    continue;
                }
            }
            if (c == '$' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                int j = i + 1;
                while (j < text.length() && Character.isDigit(text.charAt(j))) {
                    j++;
                }
                i = j;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 调试
    // ------------------------------------------------------------------

    /** 把文本压成单行并截断，便于打日志。 */
    private static String oneLine(String value) {
        return oneLine(value, 120);
    }

    private static String oneLine(String value, int limit) {
        if (value == null) {
            return "null";
        }
        String text = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }

    /**
     * 渲染成带行号的视图，便于从日志里直接看出编辑落在哪一行。
     *
     * @param limit 最多渲染多少行（越界编辑往往表现为"插入到了文件末尾"，看首尾就够）
     */
    private static String numberedLines(String value, int limit) {
        if (value == null) {
            return "null";
        }
        String[] lines = value.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(lines.length, limit);
        for (int i = 0; i < shown; i++) {
            sb.append(String.format("  %2d| ", Integer.valueOf(i)))
              .append(lines[i].replace("\t", "\\t"))
              .append('\n');
        }
        if (lines.length > shown) {
            sb.append("   …(共 ").append(lines.length).append(" 行)\n");
        }
        return sb.toString();
    }

    /** 把 LSP 范围描述成 {@code 起行:起列-终行:终列}。 */
    private static String describeRange(Range range) {
        if (range == null || range.getStart() == null || range.getEnd() == null) {
            return "null";
        }
        return range.getStart().getLine() + ":" + range.getStart().getCharacter()
                + "-" + range.getEnd().getLine() + ":" + range.getEnd().getCharacter();
    }

    /** 把每条附加编辑描述成 {@code [范围]=>文本}。 */
    private String describeExtras() {
        if (extraEdits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" {");
        for (int i = 0; i < extraEdits.size(); i++) {
            TextEdit edit = extraEdits.get(i);
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append('[').append(edit == null ? "null" : describeRange(edit.getRange())).append("]=>")
              .append('"').append(oneLine(edit == null ? null : edit.getNewText(), 60)).append('"');
        }
        return sb.append('}').toString();
    }

    public String getInsertText() {
        return insertText;
    }

    /** 会附带几条编辑（自动导包等）。 */
    public int getExtraEditCount() {
        return extraEdits.size();
    }

    /** 是否使用了服务端给的替换范围。 */
    public boolean hasServerRange() {
        return mainRange != null;
    }
}
