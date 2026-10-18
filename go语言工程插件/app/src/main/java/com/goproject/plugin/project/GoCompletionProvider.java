package com.goproject.plugin.project;

import com.goproject.plugin.lsp.bridge.GoplsCompletionConverter;
import com.goproject.plugin.lsp.bridge.GoplsCompletionService;
import com.goproject.plugin.lsp.bridge.GoplsLogger;
import com.goproject.plugin.lsp.Uris;
import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.completion.CompletionItem;
import com.rapid.api.lang.engine.completion.CompletionParams;
import com.rapid.api.lang.engine.completion.CompletionProvider;
import com.rapid.api.lang.engine.completion.CompletionResult;
import com.rapid.framework.vfs.VirtualFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 结绳语言引擎的补全入口：<b>优先问 gopls，拿不到就退回插件自带的离线补全</b>。
 *
 * <p>保留离线补全不是冗余，而是必要的兜底：gopls 是外部进程，
 * 可能没启动、端口不对、或工程里没有 go.mod。那些情况下若只剩空补全，
 * 插件就等于坏了。所以离线关键字/片段始终可用，gopls 可用时再叠加真正的语义补全
 * （点号成员、跨包符号、未导入包名等）。
 *
 * <p>线程说明：宿主的 {@code Language.requireAutoComplete} 在补全工作线程上调用本类，
 * 因此这里的阻塞式 LSP 请求是安全的；但绝不能被 UI 线程调用。
 */
public class GoCompletionProvider implements CompletionProvider {

    /** 离线补全实现（与原插件逻辑一致），只作为 gopls 不可用时的兜底。 */
    private final GoOfflineCompletion offline = new GoOfflineCompletion();

    @Override
    public void complete(CompletionParams params, CompletionResult result, CancellationToken token) {
        if (params == null || result == null) {
            return;
        }

        // 紧跟在空白之后不要弹补全：那是在排版，不是想补全。
        //
        // 判据用"光标本行左侧的字符"，不能用宿主给的 triggerChar——
        // 宿主在 onSelectionChange 那条路径会直接调 requireCompletion()，
        // 此时 triggerChar 就是 '\0'，据此判断会把空前缀也当成"用户主动补全"，
        // 于是离线片段被整批放出、超过 publisher 刷新阈值而弹框。
        String partial = params.partial == null ? "" : params.partial;
        if (partial.isEmpty() && isPrecededByWhitespace(params)) {
            return;
        }

        List<CompletionItem> items = fromGopls(params);
        if (items != null && !items.isEmpty()) {
            result.setItems(items);
            return;
        }

        // gopls 没结果（未连接 / 返回空）时退回离线补全，保证插件始终可用。
        offline.complete(params, result, token);
    }

    /**
     * 向 gopls 请求补全。
     *
     * @return 补全项；不可用或失败时返回 null（由调用方走兜底）
     */
    private List<CompletionItem> fromGopls(CompletionParams params) {
        try {
            File file = toFile(params.virtualFile);
            if (file == null || !GoplsCompletionService.supports(file)) {
                return null;
            }
            String partial = params.partial == null ? "" : params.partial;

            GoplsCompletionService.Request request = new GoplsCompletionService.Request();
            request.file = file;
            // 宿主会把编辑器里的整篇文本放进 params.content，含未保存改动。
            request.content = params.content;
            request.line = params.position == null ? 0 : params.position.line;
            // 结绳的 Position 用 character 表示列（不是 column）。
            request.column = params.position == null ? 0 : params.position.character;
            request.prefix = partial;
            request.triggerChar = params.triggerChar;
            // 有触发字符就报"触发字符"，否则报"手动触发"——后者返回的结果集更完整。
            request.triggerKind = params.triggerChar == '\0' ? 1 : 2;

            GoplsCompletionService.Result response = GoplsCompletionService.completion(request);
            if (!response.isSuccess()) {
                GoplsLogger.get().info("gopls 不可用，改用离线补全: " + response.error);
                return null;
            }
            // 引擎这条路上，最终由宿主 IpeSimpleCompletionItem / IpeSnippetCompletionItem
            // 应用编辑，而它俩是用 "光标列 - params.partial.length()" 来定位替换起点的。
            // 所以这里必须记下"宿主会拿到的两个数"，以及我们给出的 textEdit 范围——
            // 一旦两者不一致，替换区间就会错位（表现为导包位置或光标不对）。
            int cursorLine = params.position == null ? -1 : params.position.line;
            int cursorColumn = params.position == null ? -1 : params.position.character;

            List<CompletionItem> converted = new ArrayList<>(response.items.size());
            for (org.eclipse.lsp4j.CompletionItem entry : response.items) {
                // 传入光标位置：转换器会按"宿主使用的同一套坐标"生成 textEdit 范围，
                // 避免服务端范围与宿主的前缀长度算法打架。
                // 传入正文：转换器会用它在本地把多条附加编辑合并成一条，
                // 规避宿主 TextEditUtils 比较器对"纯插入"无全序关系的问题。
                CompletionItem item = GoplsCompletionConverter.toEngineItem(
                        entry, partial.length(), cursorLine, cursorColumn, params.content);
                if (item != null) {
                    converted.add(item);
                }
            }
            // 每个补全请求只打一条压缩摘要，便于在日志里逐行比对。
            GoplsLogger.get().info(summarizeCompletion(converted, params, partial));
            return converted;
        } catch (Throwable t) {
            GoplsLogger.get().warning("gopls 补全异常，改用离线补全: " + t);
            return null;
        }
    }

    /**
     * 把一次补全请求的关键状态压成<b>一行</b>。
     *
     * <p>这一行就是为了回答"宿主的替换区间和我给的 textEdit 是否一致"：
     * 宿主 {@code IpeSimpleCompletionItem} 是按
     * {@code 光标列 - params.partial.length()} 往回删的，
     * 若这个起点与 {@code textEdit.range} 的起始列不同，替换就会错位。
     * 所以要打印的三个数就是：partial、光标列、textEdit 范围。
     */
    private static String summarizeCompletion(List<CompletionItem> items,
                                              CompletionParams params, String partial) {
        int cursorColumn = params.position == null ? -1 : params.position.character;
        StringBuilder sb = new StringBuilder("补全摘要 ");
        sb.append("条数=").append(items.size())
          .append(" partial=[").append(partial).append("]")
          .append(" 光标列=").append(cursorColumn)
          .append(" 宿主会删除的起点列=").append(Math.max(0, cursorColumn - partial.length()));
        if (items.isEmpty()) {
            return sb.toString();
        }
        CompletionItem first = items.get(0);
        sb.append(" 首项=[")
          .append(first.label)
          .append("] insert=[").append(oneLine(first.insertText))
          .append("] 格式=").append(first.insertTextFormat)
          .append(" 附加编辑=").append(first.additionalTextEdits == null
                  ? 0 : first.additionalTextEdits.size());
        if (first.textEdit != null && first.textEdit.range != null
                && first.textEdit.range.start != null && first.textEdit.range.end != null) {
            sb.append(" textEdit=")
              .append(first.textEdit.range.start.line).append(':')
              .append(first.textEdit.range.start.character).append('-')
              .append(first.textEdit.range.end.line).append(':')
              .append(first.textEdit.range.end.character);
        } else {
            // textEdit 为 null 时宿主只能按 partial 长度自己算——这也是可以接受的状态。
            sb.append(" textEdit=null");
        }
        return sb.toString();
    }

    /** 压成单行，避免日志被换行拆散。 */
    private static String oneLine(String value) {
        if (value == null) {
            return "null";
        }
        String text = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    @Override
    public void resolve(CompletionItem completionItem) {
        // gopls 的结果在 complete 阶段已带 detail/documentation，无需二次请求。
        // 离线补全项自己也会填 detail，所以这里不用补。
    }

    /**
     * 光标左侧是否紧邻空白字符。
     *
     * <p>用宿主的 {@code CompletionParams.lineText}：它就是光标本行的文本，
     * 比自己从 content 里切行更省事也更准。
     */
    private static boolean isPrecededByWhitespace(com.rapid.api.lang.engine.completion.CompletionParams params) {
        String line = params.lineText;
        int column = params.position == null ? 0 : params.position.character;
        if (line == null || column <= 0 || column > line.length()) {
            return false;
        }
        return Character.isWhitespace(line.charAt(column - 1));
    }

    /** 结绳虚拟文件 → 本地 File；非本地协议返回 null。 */
    private static File toFile(VirtualFile virtualFile) {
        if (virtualFile == null) {
            return null;
        }
        try {
            java.net.URI uri = virtualFile.asURI();
            if (uri != null) {
                return Uris.toFile(uri.toString());
            }
        } catch (Throwable ignore) {
            // 取不到 URI 就退回路径。
        }
        try {
            return new File(virtualFile.getPath());
        } catch (Throwable ignore) {
            return null;
        }
    }
}
