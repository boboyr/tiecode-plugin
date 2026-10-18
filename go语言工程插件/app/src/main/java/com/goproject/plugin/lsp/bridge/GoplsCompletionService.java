package com.goproject.plugin.lsp.bridge;

import com.goproject.plugin.lsp.GoplsSession;

import org.eclipse.lsp4j.CompletionItem;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * 补全服务:把"光标处要补什么"翻译成 gopls 请求,再把结果交回给调用方。
 *
 * <p>它不负责 UI,也不负责发布,返回的是<b>原始 LSP 补全项</b>。
 * 因为插件里同时存在两套补全模型(Sora 的 {@code CompletionItem} 和
 * 结绳的 {@code com.rapid...CompletionItem}),这一层若选边站就会让另一边用不了。
 * 由调用方各自用 {@link GoplsCompletionConverter} 转换,本层保持框架无关。
 *
 * <p>连接与协议全部由 {@link GoplsSession}(基于 sora editor-lsp)承担,本层只做参数编排。
 */
public final class GoplsCompletionService {

    private GoplsCompletionService() {
    }

    /** 一次补全请求的输入。 */
    public static final class Request {

        /** 文件(用来推断 workspace 与 URI)。 */
        public File file;
        /** 整篇正文(未保存内容也从这里给出去)。 */
        public String content;
        /** 0 基行号。 */
        public int line;
        /** 0 基列号。 */
        public int column;
        /** 光标左键入的前缀,如 {@code fmt.Pr};没有则为空串。 */
        public String prefix = "";
        /** 触发字符;没有则为 {@code '\0'}。 */
        public char triggerChar;
        /** 触发方式,见 LSP:1=手动 2=触发字符 3=补全不完整。 */
        public int triggerKind = 1;
    }

    /** 一次补全请求的结果:原始 LSP 补全项 + 失败原因。 */
    public static final class Result {

        /** 原始 LSP {@code CompletionItem} 列表(未转换)。 */
        public final List<CompletionItem> items;
        /** 失败或未就绪时的原因,成功为 null。 */
        public final String error;

        Result(List<CompletionItem> items, String error) {
            this.items = items;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }

        public boolean isEmpty() {
            return items == null || items.isEmpty();
        }
    }

    private static Result empty(String error) {
        return new Result(Collections.<CompletionItem>emptyList(), error);
    }

    /**
     * 向 gopls 请求补全。
     *
     * <p>这是<b>阻塞调用</b>,必须在工作线程上执行——Sora 的补全线程本身就是工作线程,
     * 所以直接从 {@code requireAutoComplete} 里调它是安全的。
     */
    public static Result completion(Request request) {
        if (request == null || request.file == null) {
            return empty("没有文件信息");
        }
        // 取证：确认发给 gopls 的正文与坐标。若这里的内容与编辑器里的实际文本不一致
        // （缩进符、行数、是否含未保存改动），服务端算出的 range 套到文档上必然错位，
        // 自动导包就会插到错误位置。这行日志能在一次复现内排除掉这种可能。
        GoplsLogger.get().info("请求补全 " + request.file.getName()
                + " 位置=" + request.line + ":" + request.column
                + " prefix=[" + request.prefix + "]"
                + " 正文行数=" + countLines(request.content)
                + " 正文首行=["
                + firstLine(request.content) + "]");

        GoplsSession session = GoplsSession.get();
        GoplsSession.CompletionResult result = session.completion(
                request.file,
                request.content,
                request.line,
                request.column,
                request.triggerChar,
                request.triggerKind);
        if (!result.isSuccess()) {
            return empty(result.error);
        }
        GoplsLogger.get().info("gopls 补全 " + result.items.size() + " 条"
                + " prefix=[" + request.prefix + "]"
                + " triggerChar=" + (int) request.triggerChar);
        return new Result(result.items, null);
    }

    /** 统计行数，用于核对发给服务端的正文是否与编辑器一致。 */
    private static int countLines(String text) {
        if (text == null) {
            return -1;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** 首行内容（含缩进符的可见化），用于确认 tab/空格没被改写。 */
    private static String firstLine(String text) {
        if (text == null) {
            return "null";
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        line = line.replace("\t", "\\t");
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }

    /** 该文件是否值得发给 gopls。 */
    public static boolean supports(File file) {
        return GoplsSession.supports(file);
    }

    /** 供调试:查看会话是否就绪。 */
    public static boolean isReady() {
        return GoplsSession.get().isReady();
    }

    /** 在后台预热连接,不阻塞调用方。 */
    public static void warmUp(File fileHint) {
        GoplsSession.get().warmUp(fileHint);
    }
}
