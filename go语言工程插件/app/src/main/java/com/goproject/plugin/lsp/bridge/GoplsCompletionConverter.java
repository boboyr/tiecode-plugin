package com.goproject.plugin.lsp.bridge;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * LSP 补全结果 → 编辑器补全项 的转换。
 *
 * <p>纯函数,不碰 I/O、不发请求,方便单独验证。
 *
 * <p>三条关键约定:
 * <ul>
 *   <li><b>主编辑范围</b>取服务端的 {@code textEdit.range}(权威区间),
 *       没有时才让调用方按已输入前缀推算;
 *   <li><b>插入文本</b>优先 {@code textEdit.newText},其次 {@code insertText},
 *       都没有才退回 {@code label}——与 LSP 规范一致;
 *   <li><b>附加编辑</b>({@code additionalTextEdits})是自动导包的载体,
 *       两条补全通道都必须带上,否则用户补出 {@code os.Exit} 却没有
 *       {@code import "os"},就是"半成品补全"。
 * </ul>
 *
 * <p>本类直接消费 lsp4j 的 {@link CompletionItem}(由 editor-lsp 的协议层反序列化得到),
 * 不再自己解析 JSON。
 *
 * <p>注意:sora 与 lsp4j 各有一个 {@code CompletionItemKind},同名不能同时 import,
 * 因此本类 import lsp4j 的版本,sora 版本全部用全限定名书写。
 */
public final class GoplsCompletionConverter {

    private GoplsCompletionConverter() {
    }

    /** 取补全项的插入文本:textEdit.newText &gt; insertText &gt; label。 */
    public static String insertTextOf(CompletionItem item) {
        if (item == null) {
            return null;
        }
        String fromEdit = textEditNewText(item);
        if (fromEdit != null) {
            return fromEdit;
        }
        if (item.getInsertText() != null) {
            return item.getInsertText();
        }
        return item.getLabel();
    }

    /** 取主编辑范围:仅当服务端给的是标准 {@code TextEdit} 时返回。 */
    public static Range mainRangeOf(CompletionItem item) {
        if (item == null || item.getTextEdit() == null) {
            return null;
        }
        Either<TextEdit, org.eclipse.lsp4j.InsertReplaceEdit> textEdit = item.getTextEdit();
        if (textEdit.isLeft() && textEdit.getLeft() != null) {
            return textEdit.getLeft().getRange();
        }
        return null;
    }

    /** 取 {@code textEdit} 的 newText(含 InsertReplaceEdit 形态)。 */
    private static String textEditNewText(CompletionItem item) {
        Either<TextEdit, org.eclipse.lsp4j.InsertReplaceEdit> textEdit = item.getTextEdit();
        if (textEdit == null) {
            return null;
        }
        if (textEdit.isLeft() && textEdit.getLeft() != null) {
            return textEdit.getLeft().getNewText();
        }
        if (textEdit.isRight() && textEdit.getRight() != null) {
            return textEdit.getRight().getNewText();
        }
        return null;
    }

    /** 取补全项的说明文本:优先 documentation,其次 detail。 */
    public static String descriptionOf(CompletionItem item) {
        if (item == null) {
            return null;
        }
        String documentation = documentationText(item.getDocumentation());
        if (documentation != null) {
            return documentation;
        }
        return item.getDetail();
    }

    /**
     * 把一条 LSP 补全项转成 Sora 补全项。
     *
     * @param item         服务端返回的补全项
     * @param prefixLength 已输入前缀长度(仅在服务端未给 textEdit 范围时用于推算删除区间)
     */
    public static io.github.rosemoe.sora.lang.completion.CompletionItem toSoraItem(
            CompletionItem item, int prefixLength) {
        return toSoraItem(item, prefixLength, null);
    }

    /**
     * 把一条 LSP 补全项转成 Sora 补全项。
     *
     * @param partial 已输入前缀（如 {@code fmt.Pr}）。
     *                传入它，补全项就能在"没有服务端 textEdit.range"时
     *                直接在行文本里定位替换区间——不依赖宿主给的、可能过期的列号。
     */
    public static io.github.rosemoe.sora.lang.completion.CompletionItem toSoraItem(
            CompletionItem item, int prefixLength, String partial) {
        if (item == null || item.getLabel() == null || item.getLabel().isEmpty()) {
            return null;
        }
        return new GoplsCompletionItem(
                item.getLabel(),
                descriptionOf(item),
                soraKind(item.getKind()),
                insertTextOf(item),
                mainRangeOf(item),
                prefixLength,
                item.getInsertTextFormat() == InsertTextFormat.Snippet,
                item.getAdditionalTextEdits(),
                partial);
    }

    /**
     * 把一条 LSP 补全项转成结绳语言引擎的补全项。
     *
     * <p>走 {@code LanguageEngine} 的分发路径,与 {@link #toSoraItem} 并存,
     * 使插件里两条补全通道都可用。
     *
     * <p>必须同时带上 {@code textEdit} 与 {@code additionalTextEdits}:
     * 前者决定插入时替换哪段文本,后者承载自动导包;只填 {@code label}/{@code insertText}
     * 会让宿主按整行前缀猜范围,导致成员补全多删、自动导包丢失。
     */
    public static com.rapid.api.lang.engine.completion.CompletionItem toEngineItem(
            CompletionItem item, int prefixLength) {
        return toEngineItem(item, prefixLength, -1, -1);
    }

    /**
     * 把一条 LSP 补全项转成结绳语言引擎的补全项。
     *
     * <p>关于 {@code textEdit} 的范围：<b>必须与宿主的算法一致</b>。
     * 宿主 {@code IpeSimpleCompletionItem} 是按
     * {@code 光标列 - params.partial.length()} 往回删的，所以这里产出的范围也要用
     * 同一套坐标算（{@code [光标列 - 前缀长度, 光标列]}）。
     *
     * <p>如果照搬服务端的 {@code textEdit.range}，两边很可能不一致——
     * gopls 的范围有时只覆盖部分标识符，宿主却按整个 partial 长度删，
     * 两套坐标打架会把替换区间和光标一起弄错（表现为导包位置错、光标跑到别的行）。
     */
    public static com.rapid.api.lang.engine.completion.CompletionItem toEngineItem(
            CompletionItem item, int prefixLength, int cursorLine, int cursorColumn) {
        return toEngineItem(item, prefixLength, cursorLine, cursorColumn, null);
    }

    /**
     * 把一条 LSP 补全项转成结绳语言引擎的补全项。
     *
     * @param content 当前文档全文。用来把多条附加编辑<b>合并成一条</b>，
     *                规避宿主 {@code TextEditUtils} 比较器对"纯插入"没有全序关系的问题
     *                （详见 {@link #mergeAdditionalEdits}）。
     */
    public static com.rapid.api.lang.engine.completion.CompletionItem toEngineItem(
            CompletionItem item, int prefixLength, int cursorLine, int cursorColumn, String content) {
        if (item == null || item.getLabel() == null || item.getLabel().isEmpty()) {
            return null;
        }
        com.rapid.api.lang.engine.completion.CompletionItem result =
                new com.rapid.api.lang.engine.completion.CompletionItem();
        result.label = item.getLabel();
        result.detail = descriptionOf(item);
        result.deprecated = item.getDeprecated() != null && item.getDeprecated();
        result.sortText = item.getSortText();

        String insertText = insertTextOf(item);
        // 占位符剥成纯文本后再提交（见下方 insertTextFormat 的说明）。
        result.insertText = stripSnippetSyntax(insertText == null ? item.getLabel() : insertText);

        // 刻意统一按<b>纯文本</b>提交,即使服务端声明了 Snippet。
        //
        // 原因:宿主的片段路径 IpeSnippetCompletionItem 会把 additionalTextEdits.get(0)
        // 当成片段处理——
        //     text.delete(range); startSnippet(idx, parse(newText), "");
        // 而 gopls 的自动导包编辑形如 {range: 3:1-3:1, newText: "\t\"os\"\n"},
        // end 不为 null 就走这个分支,startSnippet 把光标落在插入文本<b>末尾</b>,
        // 那段文本以换行结尾,于是光标跳到下一行——这就是"导包后光标自动换行"的原因。
        //
        // 走 IpeSimpleCompletionItem 则不同:附加编辑全部交给 TextEditUtils,
        // 用 text.replace(...) 落盘且不碰光标,导包位置与光标都正确。
        //
        // 代价是失去 ${1:} 占位符跳转,所以上面先把占位符剥掉。
        result.insertTextFormat = com.rapid.api.lang.engine.completion.InsertTextFormat.PLAINTEXT;

        // 主编辑:范围按<b>宿主的算法</b>算,而不是照搬服务端范围。
        // 这样宿主"按 partial 长度删除"与"按 textEdit 替换"两条路径指向同一段文本,
        // 不会互相打架。
        boolean hasCursor = cursorLine >= 0 && cursorColumn >= 0;
        if (hasCursor) {
            int startColumn = Math.max(0, cursorColumn - prefixLength);
            if (cursorColumn >= startColumn) {
                com.rapid.api.lang.engine.baseModel.TextEdit textEdit =
                        new com.rapid.api.lang.engine.baseModel.TextEdit();
                textEdit.range = new com.rapid.framework.data.document.Range(
                        new com.rapid.framework.data.document.Position(cursorLine, startColumn),
                        new com.rapid.framework.data.document.Position(cursorLine, cursorColumn));
                textEdit.newText = result.insertText;
                result.textEdit = textEdit;
            }
        } else {
            // 拿不到光标位置时,退回服务端范围(次优,但至少不是空)。
            Range mainRange = mainRangeOf(item);
            if (mainRange != null) {
                com.rapid.api.lang.engine.baseModel.TextEdit textEdit =
                        new com.rapid.api.lang.engine.baseModel.TextEdit();
                textEdit.range = toEngineRange(mainRange);
                textEdit.newText = result.insertText;
                result.textEdit = textEdit;
            }
        }

        // 附加编辑:自动导包就走这里。
        // 多条时必须先合并成一条——宿主的 TextEditUtils 对"纯插入"的比较器
        // 恒返回 1,没有全序关系,多条纯插入的应用顺序不确定,会导致导包落错位置。
        List<TextEdit> merged = mergeAdditionalEdits(item.getAdditionalTextEdits(), content);
        List<com.rapid.api.lang.engine.baseModel.TextEdit> extras = toEngineTextEdits(merged);
        if (!extras.isEmpty()) {
            result.additionalTextEdits = extras;
        }

        result.kind = engineKind(item.getKind());
        return result;
    }

    /**
     * 把多条附加编辑合并成一条（按原文档坐标）。
     *
     * <h3>为什么必须合并</h3>
     * 宿主的 {@code TextEditUtils.performTextEdits} 用这样一个比较器排序：
     * <pre>
     * if (t1.range.start.equals(t1.range.end)) return 1;   // 纯插入
     * return -t1.range.start.compareTo(t2.range.start);
     * </pre>
     * 对两条纯插入 A、B，{@code compare(A,B)} 与 {@code compare(B,A)} 都返回 1，
     * 违反比较器的传递性要求，{@code Collections.sort} 的结果不确定。
     * gopls 的自动导包经常返回多条纯插入（例如新增 import 块 + 插入包名），
     * 顺序一乱，后面那条按原坐标算的编辑就会落错位置。
     *
     * <p>合并成一条后，宿主无论怎么排都只有一条，问题不复存在。
     *
     * @param edits   服务端给的附加编辑；0 或 1 条时原样返回
     * @param content 当前文档全文；拿不到时放弃合并（退化为原来的行为）
     */
    public static List<TextEdit> mergeAdditionalEdits(List<TextEdit> edits, String content) {
        if (edits == null || edits.size() <= 1 || content == null) {
            return edits;
        }
        // 只处理"都在同一行区间内、且互不重叠"的常见情形；
        // 出现重叠或跨行嵌套时保守放弃，避免把服务端的意图改错。
        for (TextEdit edit : edits) {
            if (edit == null || edit.getRange() == null
                    || edit.getRange().getStart() == null || edit.getRange().getEnd() == null) {
                return edits;
            }
        }
        int[] union = unionRange(edits, content);
        if (union == null) {
            return edits;
        }
        String mergedText = applyEditsToRange(content, edits, union[0], union[1]);
        if (mergedText == null) {
            return edits;
        }

        int[] startLineCol = offsetToLineColumn(content, union[0]);
        int[] endLineCol = offsetToLineColumn(content, union[1]);
        TextEdit merged = new TextEdit(
                new Range(new Position(startLineCol[0], startLineCol[1]),
                        new Position(endLineCol[0], endLineCol[1])),
                mergedText);
        List<TextEdit> result = new ArrayList<>(1);
        result.add(merged);

        if (GoplsLogger.get() != null) {
            GoplsLogger.get().info("附加编辑已合并: " + edits.size() + " 条 → 1 条"
                    + " 原始=" + describeEdits(edits)
                    + " 合并后范围=" + startLineCol[0] + ":" + startLineCol[1]
                    + "-" + endLineCol[0] + ":" + endLineCol[1]);
        }
        return result;
    }

    /** 把编辑列表描述成 {@code [起行:起列-终行:终列]=>"文本"}，用于日志核对。 */
    public static String describeEdits(List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < edits.size(); i++) {
            TextEdit edit = edits.get(i);
            if (i > 0) {
                sb.append(" | ");
            }
            if (edit == null || edit.getRange() == null
                    || edit.getRange().getStart() == null || edit.getRange().getEnd() == null) {
                sb.append("null");
                continue;
            }
            String text = edit.getNewText() == null ? "null" : edit.getNewText();
            text = text.replace("\t", "\\t").replace("\n", "\\n");
            if (text.length() > 40) {
                text = text.substring(0, 40) + "…";
            }
            sb.append('[').append(edit.getRange().getStart().getLine()).append(':')
              .append(edit.getRange().getStart().getCharacter()).append('-')
              .append(edit.getRange().getEnd().getLine()).append(':')
              .append(edit.getRange().getEnd().getCharacter()).append("]=\"").append(text).append('"');
        }
        return sb.append(']').toString();
    }

    /** 所有编辑的并集范围（全文偏移 [start, end]）。 */
    private static int[] unionRange(List<TextEdit> edits, String content) {
        int min = Integer.MAX_VALUE;
        int max = -1;
        for (TextEdit edit : edits) {
            int start = lineColumnToOffset(content, edit.getRange().getStart().getLine(),
                    edit.getRange().getStart().getCharacter());
            int end = lineColumnToOffset(content, edit.getRange().getEnd().getLine(),
                    edit.getRange().getEnd().getCharacter());
            if (start < 0 || end < 0 || end < start) {
                return null;
            }
            min = Math.min(min, start);
            max = Math.max(max, end);
        }
        if (min == Integer.MAX_VALUE || max < min) {
            return null;
        }
        return new int[] { min, max };
    }

    /**
     * 在并集区间上应用全部编辑，得到该区间最终应有的文本。
     *
     * <p>倒序应用：先算好各编辑在原文里的偏移，从后往前改，
     * 这样前面编辑的坐标始终有效。
     */
    private static String applyEditsToRange(String content, List<TextEdit> edits, int unionStart,
                                            int unionEnd) {
        String sub = content.substring(unionStart, unionEnd);
        // 转成相对并集起点的偏移。
        List<int[]> operations = new ArrayList<>(edits.size());
        List<String> texts = new ArrayList<>(edits.size());
        for (TextEdit edit : edits) {
            int start = lineColumnToOffset(content, edit.getRange().getStart().getLine(),
                    edit.getRange().getStart().getCharacter()) - unionStart;
            int end = lineColumnToOffset(content, edit.getRange().getEnd().getLine(),
                    edit.getRange().getEnd().getCharacter()) - unionStart;
            if (start < 0 || end < start || end > sub.length()) {
                return null;
            }
            operations.add(new int[] { start, end });
            texts.add(edit.getNewText() == null ? "" : edit.getNewText());
        }

        List<Integer> order = new ArrayList<>(operations.size());
        for (int i = 0; i < operations.size(); i++) {
            order.add(Integer.valueOf(i));
        }
        final List<int[]> finalOperations = operations;
        // 排序规则（决定"先做谁"，全部相对并集起点）：
        //   1) 起点大的先做——从后往前改，前面编辑的坐标始终有效；
        //   2) 起点相同时，<b>输入顺序靠后的先做</b>。
        //      这是关键：同一位置连续插入两段文本时，若按输入顺序应用，
        //      后插的那段会顶到先插内容的前面，最终顺序被写反。
        //      倒着做，才能让输入顺序靠前的文本落在最左边。
        java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer left, Integer right) {
                int leftIndex = left.intValue();
                int rightIndex = right.intValue();
                int a = finalOperations.get(leftIndex)[0];
                int b = finalOperations.get(rightIndex)[0];
                if (a != b) {
                    return b - a;
                }
                return rightIndex - leftIndex;
            }
        });

        StringBuilder sb = new StringBuilder(sub);
        for (Integer index : order) {
            int i = index.intValue();
            int[] range = operations.get(i);
            // 单条编辑出错就整体放弃合并，交回原样。
            if (range[0] > sb.length() || range[1] > sb.length()) {
                return null;
            }
            sb.replace(range[0], range[1], texts.get(i));
        }
        return sb.toString();
    }

    /** 行列 → 全文偏移（列钳到本行内容长度）。 */
    private static int lineColumnToOffset(String content, int line, int column) {
        if (content == null || line < 0 || column < 0) {
            return -1;
        }
        int currentLine = 0;
        int lineStart = 0;
        for (int i = 0; i <= content.length(); i++) {
            if (currentLine == line) {
                // 找到本行结尾（不含换行符）
                int lineEnd = content.indexOf('\n', lineStart);
                if (lineEnd < 0) {
                    lineEnd = content.length();
                }
                return Math.min(lineStart + column, lineEnd);
            }
            if (i == content.length()) {
                break;
            }
            if (content.charAt(i) == '\n') {
                currentLine++;
                lineStart = i + 1;
            }
        }
        // 行号超出文档：当作末尾。
        if (currentLine < line) {
            return content.length();
        }
        return -1;
    }

    /** 全文偏移 → {行, 列}。 */
    private static int[] offsetToLineColumn(String content, int offset) {
        int safe = Math.max(0, Math.min(offset, content.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < safe; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] { line, safe - lineStart };
    }

    /**
     * 把片段语法降级成纯文本。
     *
     * <p>{@code ${1:默认值}} 取默认值，{@code ${1}} / {@code $1} 丢弃：
     * 既然按 PLAINTEXT 提交，就绝不能把占位符原样写进代码。
     */
    public static String stripSnippetSyntax(String text) {
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

    /** lsp4j {@code TextEdit} 列表 → 结绳引擎 {@code TextEdit} 列表。 */
    public static List<com.rapid.api.lang.engine.baseModel.TextEdit> toEngineTextEdits(
            List<TextEdit> edits) {
        List<com.rapid.api.lang.engine.baseModel.TextEdit> result = new ArrayList<>();
        if (edits == null) {
            return result;
        }
        for (TextEdit edit : edits) {
            if (edit == null || edit.getRange() == null) {
                continue;
            }
            com.rapid.api.lang.engine.baseModel.TextEdit converted =
                    new com.rapid.api.lang.engine.baseModel.TextEdit();
            converted.range = toEngineRange(edit.getRange());
            converted.newText = edit.getNewText() == null ? "" : edit.getNewText();
            result.add(converted);
        }
        return result;
    }

    /** lsp4j {@code Range} → 结绳 {@code Range}。 */
    public static com.rapid.framework.data.document.Range toEngineRange(Range range) {
        if (range == null) {
            return null;
        }
        int startLine = range.getStart() == null ? 0 : Math.max(0, range.getStart().getLine());
        int startChar = range.getStart() == null ? 0 : Math.max(0, range.getStart().getCharacter());
        int endLine = range.getEnd() == null ? startLine : Math.max(0, range.getEnd().getLine());
        int endChar = range.getEnd() == null ? startChar : Math.max(0, range.getEnd().getCharacter());
        return new com.rapid.framework.data.document.Range(
                new com.rapid.framework.data.document.Position(startLine, startChar),
                new com.rapid.framework.data.document.Position(endLine, endChar));
    }

    /** LSP {@code CompletionItemKind} → Sora {@code CompletionItemKind}。 */
    public static io.github.rosemoe.sora.lang.completion.CompletionItemKind soraKind(
            CompletionItemKind lspKind) {
        if (lspKind == null) {
            return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Identifier;
        }
        switch (lspKind) {
            case Text: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Text;
            case Method: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Method;
            case Function: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Function;
            case Constructor: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Constructor;
            case Field: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Field;
            case Variable: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Variable;
            case Class: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Class;
            case Interface: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Interface;
            case Module: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Module;
            case Property: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Property;
            case Unit: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Unit;
            case Value: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Value;
            case Enum: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Enum;
            case Keyword: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Keyword;
            case Snippet: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Snippet;
            case Color: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Color;
            case File: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.File;
            case Reference: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Reference;
            case Folder: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Folder;
            case EnumMember: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.EnumMember;
            case Constant: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Constant;
            case Struct: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Struct;
            case Event: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Event;
            case Operator: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Operator;
            case TypeParameter: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.TypeParameter;
            default: return io.github.rosemoe.sora.lang.completion.CompletionItemKind.Identifier;
        }
    }

    /**
     * LSP {@code CompletionItemKind} → 结绳 {@code CompletionItemKind}。
     *
     * <p>结绳引擎的常量值与 LSP 一一对应(都从 1 开始),因此用数值映射;
     * 越界时退回 {@code Text}。
     */
    public static int engineKind(CompletionItemKind lspKind) {
        if (lspKind == null) {
            return com.rapid.api.lang.engine.completion.CompletionItemKind.Text;
        }
        int value = lspKind.getValue();
        if (value >= 1 && value <= 25) {
            return value;
        }
        return com.rapid.api.lang.engine.completion.CompletionItemKind.Text;
    }

    /** LSP 文档说明 → 纯文本(去 markdown 围栏、截断)。 */
    public static String documentationText(Either<String, MarkupContent> documentation) {
        if (documentation == null) {
            return null;
        }
        String text = documentation.isLeft() ? documentation.getLeft()
                : (documentation.getRight() == null ? null : documentation.getRight().getValue());
        if (text == null) {
            return null;
        }
        text = text.replace("```go", "").replace("```", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        int newline = text.indexOf('\n');
        if (newline > 0) {
            text = text.substring(0, newline);
        }
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    /** 便捷:把一组 LSP 补全项转成 Sora 补全项。 */
    public static List<io.github.rosemoe.sora.lang.completion.CompletionItem> toSoraItems(
            List<CompletionItem> items, int prefixLength) {
        List<io.github.rosemoe.sora.lang.completion.CompletionItem> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (CompletionItem item : items) {
            io.github.rosemoe.sora.lang.completion.CompletionItem converted =
                    toSoraItem(item, prefixLength);
            if (converted != null) {
                result.add(converted);
            }
        }
        return result;
    }
}
