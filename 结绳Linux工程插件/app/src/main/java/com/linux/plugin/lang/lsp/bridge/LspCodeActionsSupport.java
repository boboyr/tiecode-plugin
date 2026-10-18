package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspConverters;
import com.linux.plugin.lang.lsp.WorkspaceEditApplier;
import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.protocol.Uris;
import com.rapid.api.lang.engine.action.CodeAction;
import com.rapid.api.lang.engine.baseModel.DocumentRange;
import com.rapid.api.lang.engine.lint.Diagnostic;
import com.rapid.framework.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code textDocument/codeAction} 的共用实现。
 *
 * <p>结绳有三条并行的代码操作入口（{@code codeAction} / {@code refactor} /
 * {@code sourceGenerating}），请求体只有 {@code context} 不同，
 * 所以把"发请求 + 落地"抽到这里，三个 provider 各自只负责组织 context。
 *
 * <p>用组合而不是继承来复用：三个接口没有共同父接口，
 * 静态工具类比硬塞一层基类更直观。
 */
final class LspCodeActionsSupport {

    private final LspBridgeContext bridge;

    LspCodeActionsSupport(LspBridgeContext bridge) {
        this.bridge = bridge;
    }

    /**
     * 请求代码操作。
     *
     * @param documentRange 目标范围
     * @param diagnostics   相关诊断，可为空（refactor / sourceGenerating 入口就是空的）
     * @param onlyKinds     只取这些类别的操作；null 表示不过滤
     */
    List<CodeAction> request(DocumentRange documentRange, List<Diagnostic> diagnostics, List<String> onlyKinds) {
        if (documentRange == null || documentRange.virtualFile == null) {
            return Collections.emptyList();
        }
        String uri = uriOf(documentRange.virtualFile);
        if (uri == null || !isReady()) {
            return Collections.emptyList();
        }
        if (!bridge.getClient().ensureDocument(uri)) {
            return Collections.emptyList();
        }

        Map<String, Object> request = Json.obj(
                "textDocument", Json.obj("uri", uri),
                "range", LspConverters.range(documentRange.range),
                "context", buildContext(diagnostics, onlyKinds));

        Object response;
        try {
            response = bridge.getClient().requestIfSupported(
                    bridge.getClient().getCapabilities().supportsCodeAction(),
                    "textDocument/codeAction",
                    request);
        } catch (Throwable t) {
            log("代码操作请求失败: " + t.getMessage());
            return Collections.emptyList();
        }
        return LspConverters.codeActions(response, new LspConverters.CodeActionExecutor() {
            @Override
            public void execute(Map<String, Object> codeAction) {
                apply(codeAction);
            }
        });
    }

    /** 组织 LSP 的 {@code CodeActionContext}。 */
    private Map<String, Object> buildContext(List<Diagnostic> diagnostics, List<String> onlyKinds) {
        List<Object> converted = new ArrayList<>();
        if (diagnostics != null) {
            for (Diagnostic diagnostic : diagnostics) {
                if (diagnostic == null) {
                    continue;
                }
                Map<String, Object> entry = Json.obj();
                if (diagnostic.range != null) {
                    entry.put("range", LspConverters.range(diagnostic.range));
                }
                entry.put("message", diagnostic.message == null ? "" : diagnostic.message);
                if (diagnostic.source != null) {
                    entry.put("source", diagnostic.source);
                }
                if (diagnostic.code != null) {
                    entry.put("code", diagnostic.code);
                }
                converted.add(entry);
            }
        }
        Map<String, Object> context = Json.obj("diagnostics", converted);
        if (onlyKinds != null && !onlyKinds.isEmpty()) {
            context.put("only", new ArrayList<Object>(onlyKinds));
        }
        return context;
    }

    /**
     * 执行一个代码操作。
     *
     * <p>LSP 有两种收尾方式：内嵌 {@code edit} 自己改，
     * 或给一个 {@code command} 让服务端动手。两种都要支持。
     */
    void apply(Map<String, Object> codeAction) {
        if (codeAction == null) {
            return;
        }
        Map<String, Object> edit = LspConverters.codeActionEdit(codeAction);
        if (edit != null) {
            WorkspaceEditApplier.Result result = bridge.getApplier().apply(edit);
            if (result.isSuccess()) {
                log("代码操作已应用: " + result);
            } else {
                log("代码操作部分失败: " + result + " " + result.failures);
            }
            return;
        }
        Map<String, Object> command = LspConverters.codeActionCommand(codeAction);
        if (command == null) {
            log("代码操作既无 edit 也无 command，已忽略");
            return;
        }
        String commandName = Json.string(command, "command", null);
        if (commandName == null) {
            log("代码操作缺少 command 名称，已忽略");
            return;
        }
        try {
            bridge.getClient().request("workspace/executeCommand", Json.obj(
                    "command", commandName,
                    "arguments", Json.array(command, "arguments")));
        } catch (Throwable t) {
            log("执行服务端命令失败(" + commandName + "): " + t.getMessage());
        }
    }

    private boolean isReady() {
        return bridge.getClient() != null && bridge.getClient().isReady();
    }

    private static String uriOf(VirtualFile file) {
        try {
            java.net.URI uri = file.asURI();
            if (uri != null) {
                return Uris.normalize(uri.toString());
            }
        } catch (Throwable ignore) {
            // 退回按路径拼。
        }
        return Uris.fromPath(file.getPath());
    }

    private void log(String message) {
        bridge.getLogger().info(message);
    }
}
