package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspConverters;
import com.linux.plugin.lsp.json.Json;
import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.completion.CompletionItem;
import com.rapid.api.lang.engine.completion.CompletionParams;
import com.rapid.api.lang.engine.completion.CompletionProvider;
import com.rapid.api.lang.engine.completion.CompletionResult;
import com.rapid.api.lang.engine.completion.CompletionTriggerKind;
import com.rapid.framework.vfs.VirtualFile;

import java.util.List;
import java.util.Map;

/**
 * {@code textDocument/completion} 的桥接。
 *
 * <p>{@link CompletionParams} 继承自 {@code CursorParams}，
 * 所以文档与光标位置来自它自己的 {@code virtualFile} 与 {@code position} 字段。
 *
 * <p>这条路径上每一步都可能"安静地返回空"（连接没就绪、文档打不开、服务端返回空），
 * 而编辑器只会表现为"补全框不弹"。因此每个失败分支都带原因打日志——
 * 排查时直接看日志即可定位卡在哪一步，不必在编辑器里反复试。
 */
public final class LspCompletionProvider extends LspBaseProvider implements CompletionProvider {

    public LspCompletionProvider(DefaultLanguageContext context, LspBridgeContext bridge) {
        super(context, bridge);
    }

    @Override
    public void complete(CompletionParams params, CompletionResult result, CancellationToken token) {
        if (params == null || result == null) {
            return;
        }
        if (isCancelled(token)) {
            return;
        }

        VirtualFile file = params.virtualFile;
        if (file == null) {
            logger().warning("补全被跳过：编辑器没有给出虚拟文件");
            return;
        }
        String uri = openUri(file);
        if (uri == null) {
            // 两种原因：连接没就绪，或读不到文件内容。把细节打出来便于区分。
            logger().warning("补全被跳过：文档无法打开 [" + file.getName() + "]"
                    + " ready=" + ready()
                    + " path=" + safePath(file));
            return;
        }
        if (!client().getCapabilities().supportsCompletion()) {
            logger().warning("补全被跳过：服务端未声明 completion 能力");
            return;
        }

        Map<String, Object> request = textDocumentPosition(uri, params.position);
        request.put("context", triggerContext(params));

        Object response;
        long started = System.currentTimeMillis();
        try {
            response = client().request("textDocument/completion", request);
        } catch (Throwable t) {
            logger().warning("补全请求失败: " + t.getMessage());
            return;
        }
        long elapsed = System.currentTimeMillis() - started;

        if (response == null) {
            logger().info("补全返回 null（" + elapsed + "ms）: " + file.getName());
            return;
        }

        boolean[] incomplete = new boolean[1];
        List<CompletionItem> items = LspConverters.completionItems(response, incomplete);
        if (items.isEmpty()) {
            logger().info("补全返回 0 条（" + elapsed + "ms）"
                    + " partial=[" + nullable(params.partial) + "]"
                    + " triggerChar=" + (int) params.triggerChar);
            return;
        }

        // 保持服务端返回顺序：LSP 用 sortText 决定排序，客户端不该重排。
        result.setItems(items);
        logger().info("补全返回 " + items.size() + " 条（" + elapsed + "ms）"
                + " partial=[" + nullable(params.partial) + "]"
                + " triggerChar=" + (int) params.triggerChar
                + " 首项=" + items.get(0).label);
    }

    /**
     * 构造 LSP 的 {@code context} 字段，把"用户敲了什么"告诉服务端。
     *
     * <p>触发字符为空时按 {@code Invoked} 处理——服务端会返回更完整的结果集，
     * 比谎报一个触发字符更安全。
     */
    private Map<String, Object> triggerContext(CompletionParams params) {
        char triggerChar = params.triggerChar;
        if (triggerChar == '\0') {
            return Json.obj("triggerKind", Integer.valueOf(CompletionTriggerKind.INVOKED));
        }
        return Json.obj(
                "triggerKind", Integer.valueOf(CompletionTriggerKind.TRIGGER_CHARACTER),
                "triggerCharacter", String.valueOf(triggerChar));
    }

    /**
     * 读取取消标记。
     *
     * <p>{@code CancellationToken} 上有个公开字段 {@code isCancellationRequested}；
     * 用它可以在用户继续输入时放弃这次补全，避免旧结果闪一下。
     */
    private static boolean isCancelled(CancellationToken token) {
        try {
            return token != null && token.isCancellationRequested;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String nullable(String text) {
        return text == null ? "" : text;
    }

    private static String safePath(VirtualFile file) {
        try {
            return file.getPath();
        } catch (Throwable t) {
            return "(取路径失败)";
        }
    }

    @Override
    public void resolve(CompletionItem item) {
        // 结绳在当前调用路径上不会触发 resolve（complete 返回时已拿到完整条目），
        // 因此这里刻意留空。若将来服务端把 documentation 延后到 resolve，
        // 可在此对 item.data 发 completionItem/resolve 再回填。
    }
}
