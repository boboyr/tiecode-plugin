package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspConverters;
import com.linux.plugin.lsp.json.Json;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.highlight.DocumentHighlight;
import com.rapid.api.lang.engine.highlight.HighlightProvider;
import com.rapid.api.lang.engine.lens.CodeLens;
import com.rapid.api.lang.engine.lens.CodeLensProvider;
import com.rapid.framework.data.document.Range;
import com.rapid.framework.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 文档内高亮与 CodeLens 的桥接。
 *
 * <p>这两个服务都返回"带范围的小标记"，结构相近，放一处。其中 CodeLens
 * 在 LSP 里可能要先 resolve 才能拿到命令，这里只暴露范围与可执行动作，
 * 不做自动 resolve，避免为用户根本没看的每一行发请求。
 */
public final class LspHighlightProviders {

    private LspHighlightProviders() {
    }

    /** {@code textDocument/documentHighlight}（按位置查当前符号的所有出现）。 */
    public static final class HighlightAtPosition extends LspBaseProvider implements HighlightProvider {

        public HighlightAtPosition(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<DocumentHighlight> documentHighlights(VirtualFile file,
                                                         com.rapid.api.lang.engine.CancellationToken token) {
            String uri = openUri(file);
            if (uri == null) {
                return Collections.emptyList();
            }
            // 结绳这个接口只给文件、不给光标位置，因此退化为"文档级高亮"，
            // 用文档首行首列作为位置——clangd 等实现会返回整个文件的相关符号。
            Map<String, Object> request = Json.obj(
                    "textDocument", Json.obj("uri", uri),
                    "position", Json.obj("line", Integer.valueOf(0), "character", Integer.valueOf(0)));
            try {
                Object response = client().requestIfSupported(
                        client().getCapabilities().supportsDocumentHighlight(),
                        "textDocument/documentHighlight",
                        request);
                return LspConverters.documentHighlights(response);
            } catch (Throwable t) {
                logger().warning("符号高亮请求失败: " + t.getMessage());
                return Collections.emptyList();
            }
        }
    }

    /** {@code textDocument/codeLens}。 */
    public static final class CodeLenses extends LspBaseProvider implements CodeLensProvider {

        public CodeLenses(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<CodeLens> codeLens(VirtualFile file) {
            String uri = openUri(file);
            if (uri == null) {
                return Collections.emptyList();
            }
            Object response;
            try {
                response = client().requestIfSupported(
                        client().getCapabilities().supportsCodeLens(),
                        "textDocument/codeLens",
                        Json.obj("textDocument", Json.obj("uri", uri)));
            } catch (Throwable t) {
                logger().warning("CodeLens 请求失败: " + t.getMessage());
                return Collections.emptyList();
            }

            List<Object> entries = Json.array(response);
            List<CodeLens> result = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                Map<String, Object> object = Json.object(entry);
                if (object == null) {
                    continue;
                }
                CodeLens lens = new CodeLens();
                Map<String, Object> rangeValue = Json.object(object, "range");
                lens.range = rangeValue == null ? Range.NONE : LspConverters.range(rangeValue);
                final Map<String, Object> payload = object;
                lens.work = new Runnable() {
                    @Override
                    public void run() {
                        execute(payload);
                    }
                };
                result.add(lens);
            }
            return result;
        }

        /**
         * 执行 CodeLens：先 resolve（若服务端需要），再跑它的命令。
         *
         * <p>LSP 的 CodeLens 在未 resolve 时通常只有 {@code range}，
         * 命令藏在 {@code codeLens/resolve} 的结果里。
         */
        private void execute(Map<String, Object> lens) {
            Map<String, Object> resolved = lens;
            if (lens.get("command") == null) {
                try {
                    Object response = client().request("codeLens/resolve", lens);
                    Map<String, Object> object = Json.object(response);
                    if (object != null) {
                        resolved = object;
                    }
                } catch (Throwable t) {
                    logger().warning("解析 CodeLens 失败: " + t.getMessage());
                    return;
                }
            }
            Map<String, Object> command = Json.object(resolved, "command");
            if (command == null) {
                return;
            }
            String commandName = Json.string(command, "command", null);
            if (commandName == null) {
                return;
            }
            try {
                client().request("workspace/executeCommand", Json.obj(
                        "command", commandName,
                        "arguments", Json.array(command, "arguments")));
            } catch (Throwable t) {
                logger().warning("执行 CodeLens 命令失败(" + commandName + "): " + t.getMessage());
            }
        }
    }
}
