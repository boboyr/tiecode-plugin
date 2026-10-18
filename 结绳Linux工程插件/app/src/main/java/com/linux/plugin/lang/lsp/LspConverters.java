package com.linux.plugin.lang.lsp;

import com.linux.plugin.lsp.json.Json;
import com.rapid.api.lang.engine.action.CodeAction;
import com.rapid.api.lang.engine.baseModel.MarkupContent;
import com.rapid.api.lang.engine.baseModel.TextEdit;
import com.rapid.api.lang.engine.completion.CompletionItem;
import com.rapid.api.lang.engine.completion.CompletionItemKind;
import com.rapid.api.lang.engine.folding.FoldingRange;
import com.rapid.api.lang.engine.highlight.DocumentHighlight;
import com.rapid.api.lang.engine.lint.Diagnostic;
import com.rapid.api.lang.engine.lint.DiagnosticSeverity;
import com.rapid.api.lang.engine.signature.ParameterInfo;
import com.rapid.api.lang.engine.signature.SignatureHelp;
import com.rapid.api.lang.engine.signature.SignatureInfo;
import com.rapid.api.lang.engine.symbol.DocumentSymbol;
import com.rapid.api.lang.engine.symbol.SymbolInfo;
import com.rapid.framework.data.document.Location;
import com.rapid.framework.data.document.Position;
import com.rapid.framework.data.document.Range;
import com.rapid.framework.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LSP 响应 → 结绳语言服务模型 的转换。
 *
 * <p>只做纯数据转换，不碰 I/O、不发请求，方便单独验证。
 *
 * <p>关键约定：LSP 与结绳的 {@code Position/Range} 都是 <b>0 基</b>行、0 基列，
 * 因此不需要做任何加减。
 */
public final class LspConverters {

    private LspConverters() {
    }

    // ------------------------------------------------------------------
    // 位置 / 范围
    // ------------------------------------------------------------------

    /** LSP {@code Position} → 结绳 {@code Position}。 */
    public static Position position(Map<String, Object> value) {
        if (value == null) {
            return Position.NONE;
        }
        return new Position(Json.integer(value, "line", 0), Json.integer(value, "character", 0));
    }

    /** 结绳 {@code Position} → LSP {@code Position} 对象。 */
    public static Map<String, Object> position(Position value) {
        if (value == null) {
            return Json.obj("line", Integer.valueOf(0), "character", Integer.valueOf(0));
        }
        return Json.obj("line", Integer.valueOf(value.line), "character", Integer.valueOf(value.character));
    }

    /** LSP {@code Range} → 结绳 {@code Range}。 */
    public static Range range(Map<String, Object> value) {
        if (value == null) {
            return Range.NONE;
        }
        return new Range(position(Json.object(value, "start")), position(Json.object(value, "end")));
    }

    /** 结绳 {@code Range} → LSP {@code Range} 对象。 */
    public static Map<String, Object> range(Range value) {
        if (value == null) {
            Map<String, Object> origin = position((Position) null);
            return Json.obj("start", origin, "end", position((Position) null));
        }
        return Json.obj("start", position(value.start), "end", position(value.end));
    }

    /** 单点位置组成的"空范围"，用于 references 这类只关心插入点的请求。 */
    public static Map<String, Object> emptyRange(Position value) {
        Map<String, Object> point = position(value);
        return Json.obj("start", point, "end", position(value));
    }

    // ------------------------------------------------------------------
    // 跳转位置
    // ------------------------------------------------------------------

    /**
     * 解析 definition / implementation / reference 的返回值。
     *
     * <p>可能形态：{@code Location}、{@code Location[]}、单个 {@code LocationLink}、
     * {@code LocationLink[]}，以及 {@code null}。全部归一化成位置列表。
     */
    public static List<Location> locations(Object value, VirtualFileResolver resolver) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<Object> entries = value instanceof List ? Json.array(value) : Json.arr(value);
        List<Location> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            Location location = location(object, resolver);
            if (location != null) {
                result.add(location);
            }
        }
        return result;
    }

    /**
     * 单个 {@code Location} 或 {@code LocationLink} → 结绳 {@code Location}。
     *
     * <p>{@code LocationLink} 取 {@code targetUri} + {@code targetSelectionRange}：
     * 跳转应落在标识符本身而不是整个语法块。
     */
    public static Location location(Map<String, Object> object, VirtualFileResolver resolver) {
        String uri = Json.string(object, "uri", null);
        Map<String, Object> rangeValue = Json.object(object, "range");
        if (uri == null) {
            // LocationLink 形态。
            uri = Json.string(object, "targetUri", null);
            Map<String, Object> selection = Json.object(object, "targetSelectionRange");
            rangeValue = selection != null ? selection : Json.object(object, "targetRange");
        }
        if (uri == null) {
            return null;
        }
        VirtualFile file = resolver == null ? null : resolver.resolve(uri);
        if (file == null) {
            return null;
        }
        return new Location(file, range(rangeValue));
    }

    // ------------------------------------------------------------------
    // 补全
    // ------------------------------------------------------------------

    /**
     * 解析 {@code textDocument/completion} 的返回值。
     *
     * <p>可能是 {@code CompletionItem[]}、{@code CompletionList} 或 {@code null}。
     *
     * @param incomplete 输出参数：调用处传长度为 1 的数组，用于回填 isIncomplete
     */
    public static List<CompletionItem> completionItems(Object value, boolean[] incomplete) {
        List<Object> entries;
        if (value instanceof Map) {
            Map<String, Object> list = Json.object(value);
            entries = Json.array(list, "items");
            if (incomplete != null && incomplete.length > 0) {
                incomplete[0] = Json.bool(list, "isIncomplete", false);
            }
        } else {
            entries = Json.array(value);
            if (incomplete != null && incomplete.length > 0) {
                incomplete[0] = false;
            }
        }
        List<CompletionItem> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            result.add(completionItem(object));
        }
        return result;
    }

    /** 单个 LSP CompletionItem → 结绳 CompletionItem。 */
    public static CompletionItem completionItem(Map<String, Object> object) {
        CompletionItem item = new CompletionItem();
        item.label = Json.string(object, "label", "");
        item.detail = Json.string(object, "detail", null);
        item.documentation = documentation(object.get("documentation"));
        item.deprecated = Json.bool(object, "deprecated", false);
        item.preselect = Json.bool(object, "preselect", false);
        item.sortText = Json.string(object, "sortText", null);
        item.insertTextFormat = Json.integer(object, "insertTextFormat",
                com.rapid.api.lang.engine.completion.InsertTextFormat.PLAINTEXT);

        // insertText 缺省时以 label 代替（LSP 规定：无 textEdit 时用 label 插入）。
        String insertText = Json.string(object, "insertText", null);
        Map<String, Object> textEdit = Json.object(object, "textEdit");
        if (textEdit != null) {
            item.textEdit = textEdit(textEdit);
            if (insertText == null) {
                insertText = Json.string(textEdit, "newText", null);
            }
        }
        item.insertText = insertText == null ? item.label : insertText;

        List<Object> additional = Json.array(object, "additionalTextEdits");
        if (!additional.isEmpty()) {
            List<TextEdit> edits = new ArrayList<>(additional.size());
            for (Object entry : additional) {
                Map<String, Object> edit = Json.object(entry);
                if (edit != null) {
                    edits.add(textEdit(edit));
                }
            }
            item.additionalTextEdits = edits;
        }

        item.kind = completionKind(Json.integer(object, "kind", 1));
        return item;
    }

    /** LSP {@code CompletionItemKind} → 结绳 {@code CompletionItemKind}。 */
    public static int completionKind(int lspKind) {
        switch (lspKind) {
            case 1: // Text
                return CompletionItemKind.Text;
            case 2: // Method
                return CompletionItemKind.Method;
            case 3: // Function
                return CompletionItemKind.Function;
            case 4: // Constructor
                return CompletionItemKind.Constructor;
            case 5: // Field
                return CompletionItemKind.Field;
            case 6: // Variable
                return CompletionItemKind.Variable;
            case 7: // Class
                return CompletionItemKind.Class;
            case 8: // Interface
                return CompletionItemKind.Interface;
            case 9: // Module
                return CompletionItemKind.Module;
            case 10: // Property
                return CompletionItemKind.Property;
            case 11: // Unit
                return CompletionItemKind.Unit;
            case 12: // Value
                return CompletionItemKind.Value;
            case 13: // Enum
                return CompletionItemKind.Enum;
            case 14: // Keyword
                return CompletionItemKind.Keyword;
            case 15: // Snippet
                return CompletionItemKind.Snippet;
            case 16: // Color
                return CompletionItemKind.Color;
            case 17: // File
                return CompletionItemKind.File;
            case 18: // Reference
                return CompletionItemKind.Reference;
            case 19: // Folder
                return CompletionItemKind.Folder;
            case 20: // EnumMember
                return CompletionItemKind.EnumMember;
            case 21: // Constant
                return CompletionItemKind.Constant;
            case 22: // Struct
                return CompletionItemKind.Struct;
            case 23: // Event
                return CompletionItemKind.Event;
            case 24: // Operator
                return CompletionItemKind.Operator;
            case 25: // TypeParameter
                return CompletionItemKind.TypeParameter;
            default:
                return CompletionItemKind.Text;
        }
    }

    /** LSP {@code TextEdit} → 结绳 {@code TextEdit}。 */
    public static TextEdit textEdit(Map<String, Object> object) {
        return new TextEdit(range(Json.object(object, "range")), Json.string(object, "newText", ""));
    }

    /** LSP {@code TextEdit[]} → 结绳 {@code TextEdit} 列表。 */
    public static List<TextEdit> textEdits(Object value) {
        List<Object> entries = Json.array(value);
        List<TextEdit> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object != null) {
                result.add(textEdit(object));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 文档说明（hover / completion documentation）
    // ------------------------------------------------------------------

    /**
     * LSP 文档说明 → 结绳 {@code MarkupContent}。
     *
     * <p>LSP 允许三种形态：字符串、{@code {kind, value}}、或它们的数组。
     */
    public static MarkupContent documentation(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return text((String) value);
        }
        if (value instanceof List) {
            List<Object> parts = Json.array(value);
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof String) {
                    sb.append((String) part);
                } else {
                    Map<String, Object> object = Json.object(part);
                    if (object != null) {
                        if (sb.length() > 0) {
                            sb.append("\n\n");
                        }
                        sb.append(Json.string(object, "value", ""));
                    }
                }
            }
            return text(sb.toString());
        }
        Map<String, Object> object = Json.object(value);
        if (object == null) {
            return null;
        }
        return new MarkupContent(Json.string(object, "kind", "markdown"), Json.string(object, "value", ""));
    }

    /** 按 {@code kind} 选择 markdown 还是 plaintext 的文档说明。 */
    private static MarkupContent text(String value) {
        return new MarkupContent("markdown", value);
    }

    // ------------------------------------------------------------------
    // 签名帮助
    // ------------------------------------------------------------------

    /** LSP {@code SignatureHelp} → 结绳 {@code SignatureHelp}。 */
    public static SignatureHelp signatureHelp(Object value) {
        Map<String, Object> object = Json.object(value);
        if (object == null) {
            return null;
        }
        SignatureHelp help = new SignatureHelp();
        help.activeSignature = Json.integer(object, "activeSignature", 0);
        help.activeParameter = Json.integer(object, "activeParameter", 0);

        List<Object> signatures = Json.array(object, "signatures");
        List<SignatureInfo> infos = new ArrayList<>(signatures.size());
        for (Object entry : signatures) {
            Map<String, Object> signature = Json.object(entry);
            if (signature == null) {
                continue;
            }
            SignatureInfo info = new SignatureInfo();
            info.label = Json.string(signature, "label", "");
            List<Object> parameters = Json.array(signature, "parameters");
            if (!parameters.isEmpty()) {
                List<ParameterInfo> parameterInfos = new ArrayList<>(parameters.size());
                for (Object parameterEntry : parameters) {
                    Map<String, Object> parameter = Json.object(parameterEntry);
                    if (parameter == null) {
                        continue;
                    }
                    ParameterInfo parameterInfo = new ParameterInfo();
                    parameterInfo.label = parameterLabel(parameter.get("label"));
                    parameterInfos.add(parameterInfo);
                }
                info.parameters = parameterInfos;
            }
            infos.add(info);
        }
        help.signatures = infos;
        return help;
    }

    /** 参数标签可能是字符串，也可能是 {@code [start, end]} 下标对。 */
    private static String parameterLabel(Object value) {
        if (value instanceof List) {
            List<Object> offsets = Json.array(value);
            if (offsets.size() == 2) {
                return Json.string(offsets.get(0), "?") + ".." + Json.string(offsets.get(1), "?");
            }
            return "";
        }
        return Json.string(value, "");
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /** LSP 诊断数组 → 结绳 {@code Diagnostic} 列表。 */
    public static List<Diagnostic> diagnostics(List<Object> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        List<Diagnostic> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            result.add(diagnostic(object));
        }
        return result;
    }

    /** 单条 LSP 诊断 → 结绳 {@code Diagnostic}。 */
    public static Diagnostic diagnostic(Map<String, Object> object) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.range = range(Json.object(object, "range"));
        diagnostic.severity = severity(Json.integer(object, "severity", 1));
        diagnostic.message = Json.string(object, "message", "");
        Object code = object.get("code");
        diagnostic.code = code == null ? null : Json.string(code, null);
        diagnostic.source = Json.string(object, "source", null);

        List<Object> tags = Json.array(object, "tags");
        if (!tags.isEmpty()) {
            List<Integer> mapped = new ArrayList<>(tags.size());
            for (Object tag : tags) {
                int value = Json.integer(tag, 0);
                if (value > 0) {
                    mapped.add(Integer.valueOf(value));
                }
            }
            diagnostic.tags = mapped;
        }
        return diagnostic;
    }

    /**
     * LSP 严重级别 → 结绳 {@code DiagnosticSeverity}。
     *
     * <p>两者的数值方向相反：LSP 1=Error…4=Hint，结绳 5=ERROR…1=TYPO，
     * 所以必须显式映射，不能直接透传。
     */
    public static int severity(int lspSeverity) {
        switch (lspSeverity) {
            case 1:
                return DiagnosticSeverity.ERROR;
            case 2:
                return DiagnosticSeverity.WARNING;
            case 3:
                return DiagnosticSeverity.INFO;
            case 4:
                return DiagnosticSeverity.HINT;
            default:
                return DiagnosticSeverity.INFO;
        }
    }

    // ------------------------------------------------------------------
    // 符号
    // ------------------------------------------------------------------

    /**
     * 解析 {@code textDocument/documentSymbol} 返回值。
     *
     * <p>两种形态：扁平的 {@code SymbolInformation[]}，或带 children 的
     * {@code DocumentSymbol[]}。按是否含 {@code location} 字段区分。
     */
    public static List<DocumentSymbol> documentSymbols(Object value) {
        List<Object> entries = Json.array(value);
        List<DocumentSymbol> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            result.add(documentSymbol(object));
        }
        return result;
    }

    /** 单个 LSP 符号 → 结绳 {@code DocumentSymbol}（递归处理 children）。 */
    public static DocumentSymbol documentSymbol(Map<String, Object> object) {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.name = Json.string(object, "name", "");
        symbol.detail = Json.string(object, "detail", null);
        symbol.kind = symbolKind(Json.integer(object, "kind", 1));
        symbol.deprecated = Json.bool(object, "deprecated", false);

        // DocumentSymbol 用 range；SymbolInformation 用 location.range。
        Map<String, Object> rangeValue = Json.object(object, "range");
        if (rangeValue == null) {
            Map<String, Object> location = Json.object(object, "location");
            rangeValue = Json.object(location, "range");
        }
        symbol.range = range(rangeValue);
        Map<String, Object> selection = Json.object(object, "selectionRange");
        symbol.selectionRange = selection == null ? symbol.range : range(selection);

        List<Object> children = Json.array(object, "children");
        if (!children.isEmpty()) {
            List<DocumentSymbol> childSymbols = new ArrayList<>(children.size());
            for (Object child : children) {
                Map<String, Object> childObject = Json.object(child);
                if (childObject != null) {
                    childSymbols.add(documentSymbol(childObject));
                }
            }
            symbol.children = childSymbols;
        }
        return symbol;
    }

    /** 解析 {@code workspace/symbol} 返回值 → {@link SymbolInfo} 列表。 */
    public static List<SymbolInfo> symbolInfos(Object value, VirtualFileResolver resolver) {
        List<Object> entries = Json.array(value);
        List<SymbolInfo> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            Location location = location(Json.object(object, "location"), resolver);
            if (location == null) {
                continue;
            }
            SymbolInfo info = new SymbolInfo();
            info.name = Json.string(object, "name", "");
            info.kind = symbolKind(Json.integer(object, "kind", 1));
            info.deprecated = Json.bool(object, "deprecated", false);
            info.containerName = Json.string(object, "containerName", null);
            info.location = location;
            result.add(info);
        }
        return result;
    }

    /**
     * LSP {@code SymbolKind} → 结绳 {@code SymbolKind}。
     *
     * <p>注意两者虽然都从 1 开始，但枚举顺序不同（例如 LSP 5=Class 对结绳 CLASS=5 恰好一致，
     * 而 LSP 11=Constructor 在结绳是 11=CONSTRUCTOR 也一致；真正分歧在 LSP 9/10
     * 是 Method/Function，结绳是 METHOD/FUNCTION，同样一致）。
     * 为保证将来任一协议调整都不出错，这里仍逐项显式映射。
     */
    public static int symbolKind(int lspKind) {
        switch (lspKind) {
            case 1: return com.rapid.api.lang.engine.symbol.SymbolKind.FILE;
            case 2: return com.rapid.api.lang.engine.symbol.SymbolKind.MODULE;
            case 3: return com.rapid.api.lang.engine.symbol.SymbolKind.NAMESPACE;
            case 4: return com.rapid.api.lang.engine.symbol.SymbolKind.PACKAGE;
            case 5: return com.rapid.api.lang.engine.symbol.SymbolKind.CLASS;
            case 6: return com.rapid.api.lang.engine.symbol.SymbolKind.INTERFACE;
            case 7: return com.rapid.api.lang.engine.symbol.SymbolKind.ENUM;
            case 8: return com.rapid.api.lang.engine.symbol.SymbolKind.STRUCT;
            case 9: return com.rapid.api.lang.engine.symbol.SymbolKind.METHOD;
            case 10: return com.rapid.api.lang.engine.symbol.SymbolKind.FUNCTION;
            case 11: return com.rapid.api.lang.engine.symbol.SymbolKind.CONSTRUCTOR;
            case 12: return com.rapid.api.lang.engine.symbol.SymbolKind.EVENT;
            case 13: return com.rapid.api.lang.engine.symbol.SymbolKind.PROPERTY;
            case 14: return com.rapid.api.lang.engine.symbol.SymbolKind.FIELD;
            case 15: return com.rapid.api.lang.engine.symbol.SymbolKind.ENUM_MEMBER;
            case 16: return com.rapid.api.lang.engine.symbol.SymbolKind.VARIABLE;
            case 17: return com.rapid.api.lang.engine.symbol.SymbolKind.CONSTANT;
            case 18: return com.rapid.api.lang.engine.symbol.SymbolKind.TYPE_PARAMETER;
            case 19: return com.rapid.api.lang.engine.symbol.SymbolKind.STRING;
            case 20: return com.rapid.api.lang.engine.symbol.SymbolKind.NUMBER;
            case 21: return com.rapid.api.lang.engine.symbol.SymbolKind.BOOLEAN;
            case 22: return com.rapid.api.lang.engine.symbol.SymbolKind.ARRAY;
            case 23: return com.rapid.api.lang.engine.symbol.SymbolKind.OBJECT;
            case 24: return com.rapid.api.lang.engine.symbol.SymbolKind.KEY;
            case 25: return com.rapid.api.lang.engine.symbol.SymbolKind.NULL;
            case 26: return com.rapid.api.lang.engine.symbol.SymbolKind.KEYWORD;
            default:
                return com.rapid.api.lang.engine.symbol.SymbolKind.UNKNWON;
        }
    }

    // ------------------------------------------------------------------
    // 折叠
    // ------------------------------------------------------------------

    /**
     * 解析 {@code textDocument/foldingRange} 返回值。
     *
     * <p>LSP 的 {@code startCharacter/endCharacter} 可缺省，缺省时置 0。
     */
    public static List<FoldingRange> foldingRanges(Object value) {
        List<Object> entries = Json.array(value);
        List<FoldingRange> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            FoldingRange range = new FoldingRange();
            range.startLine = Json.integer(object, "startLine", 0);
            range.startCharacter = Json.integer(object, "startCharacter", 0);
            range.endLine = Json.integer(object, "endLine", 0);
            range.endCharacter = Json.integer(object, "endCharacter", 0);
            range.kind = normalizeFoldingKind(Json.string(object, "kind", null));
            result.add(range);
        }
        return result;
    }

    /** LSP 折叠类型 → 结绳 {@code FoldingRangeKind}；未知类型返回 null（不要臆造）。 */
    private static String normalizeFoldingKind(String lspKind) {
        if (lspKind == null) {
            return null;
        }
        switch (lspKind) {
            case "comment":
                return com.rapid.api.lang.engine.folding.FoldingRangeKind.COMMENT;
            case "imports":
                return com.rapid.api.lang.engine.folding.FoldingRangeKind.IMPORTS;
            case "region":
                return com.rapid.api.lang.engine.folding.FoldingRangeKind.REGION;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // 高亮
    // ------------------------------------------------------------------

    /**
     * 解析 {@code textDocument/documentHighlight} 返回值。
     *
     * <p>LSP 的 {@code kind}（1=Text/2=Read/3=Write）在结绳里没有对应字段，
     * 这里统一映射为普通高亮，靠 {@code bold} 区分读写没有可靠依据，故不加粗。
     */
    public static List<DocumentHighlight> documentHighlights(Object value) {
        List<Object> entries = Json.array(value);
        List<DocumentHighlight> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            DocumentHighlight highlight = new DocumentHighlight();
            highlight.range = range(Json.object(object, "range"));
            result.add(highlight);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 代码操作
    // ------------------------------------------------------------------

    /**
     * 解析 {@code textDocument/codeAction} 返回值（{@code CodeAction} 或 {@code Command}）。
     *
     * <p>结绳的 {@link CodeAction} 只有 {@code title} 与一个 {@code Runnable}，
     * 所以这里把"服务端要怎么改"和"怎么落到文件上"分开：
     * 本方法只负责取出标题与原始载荷，落地由 {@code executor} 注入，
     * 使转换层保持纯函数、不依赖 I/O。
     *
     * @param executor 选中该项时执行的动作
     */
    public static List<CodeAction> codeActions(Object value, CodeActionExecutor executor) {
        List<Object> entries = Json.array(value);
        List<CodeAction> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            String title = Json.string(object, "title", null);
            if (title == null) {
                // Command 形态：标题在 command 里。
                title = Json.string(Json.object(object, "command"), "title", "");
            }
            CodeAction action = new CodeAction();
            action.title = title;
            if (executor != null) {
                final Map<String, Object> payload = object;
                action.work = new Runnable() {
                    @Override
                    public void run() {
                        executor.execute(payload);
                    }
                };
            }
            result.add(action);
        }
        return result;
    }

    /** 代码操作被选中时的落地动作。 */
    public interface CodeActionExecutor {
        void execute(Map<String, Object> codeAction);
    }

    /** 取代码操作背后要执行的 LSP 命令（Command 形态），没有则返回 null。 */
    public static Map<String, Object> codeActionCommand(Map<String, Object> object) {
        return Json.object(object, "command");
    }

    /** 取代码操作内嵌的 workspaceEdit，没有则返回 null。 */
    public static Map<String, Object> codeActionEdit(Map<String, Object> object) {
        return Json.object(object, "edit");
    }
}
