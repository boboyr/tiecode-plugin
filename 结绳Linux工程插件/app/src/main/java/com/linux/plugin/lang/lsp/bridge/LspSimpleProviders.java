package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspConverters;
import com.linux.plugin.lang.lsp.LspDiagnostics;
import com.linux.plugin.lsp.json.Json;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.baseModel.DocumentPosition;
import com.rapid.api.lang.engine.folding.FoldingProvider;
import com.rapid.api.lang.engine.folding.FoldingRange;
import com.rapid.api.lang.engine.lint.Diagnostic;
import com.rapid.api.lang.engine.lint.LintProvider;
import com.rapid.api.lang.engine.signature.SignatureHelp;
import com.rapid.api.lang.engine.signature.SignatureProvider;
import com.rapid.api.lang.engine.symbol.DocumentSymbol;
import com.rapid.api.lang.engine.symbol.SymbolInfo;
import com.rapid.api.lang.engine.symbol.SymbolProvider;
import com.rapid.framework.vfs.VirtualFile;

import java.util.Collections;
import java.util.List;

/**
 * 一批"一问一答、结果直接映射"的语言服务桥接。
 *
 * <p>拆成一个文件里的多个静态内部类，而不是每类一个文件：
 * 它们的逻辑结构完全一致（取参数 → 发请求 → 转换），
 * 放一起更容易对比检查，也避免十几个二十行的碎片文件。
 */
public final class LspSimpleProviders {

    private LspSimpleProviders() {
    }

    /** {@code textDocument/signatureHelp}。 */
    public static final class Signature extends LspBaseProvider implements SignatureProvider {

        public Signature(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public boolean shouldTrigger(String text) {
            if (text == null || text.isEmpty() || !ready()) {
                return false;
            }
            List<String> triggers = client().getCapabilities().getSignatureTriggerCharacters();
            // 服务端没声明触发字符时不主动弹窗，避免每次敲字符都打扰用户。
            if (triggers == null || triggers.isEmpty()) {
                return false;
            }
            String last = text.substring(text.length() - 1);
            return triggers.contains(last);
        }

        @Override
        public SignatureHelp signatureHelp(DocumentPosition documentPosition,
                                          com.rapid.api.lang.engine.CancellationToken token) {
            String uri = openUri(documentPosition == null ? null : documentPosition.virtualFile);
            if (uri == null) {
                return null;
            }
            Object response;
            try {
                response = client().requestIfSupported(
                        client().getCapabilities().supportsSignatureHelp(),
                        "textDocument/signatureHelp",
                        textDocumentPosition(uri, documentPosition.position));
            } catch (Throwable t) {
                logger().warning("签名帮助请求失败: " + t.getMessage());
                return null;
            }
            return LspConverters.signatureHelp(response);
        }
    }

    /** {@code textDocument/documentSymbol} 与 {@code workspace/symbol}。 */
    public static final class Symbols extends LspBaseProvider implements SymbolProvider {

        public Symbols(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<DocumentSymbol> documentSymbols(VirtualFile file,
                                                   com.rapid.api.lang.engine.CancellationToken token) {
            logger().info("SymbolProvider.documentSymbols 被调用");
            String uri = openUri(file);
            if (uri == null) {
                return Collections.emptyList();
            }
            try {
                Object response = client().requestIfSupported(
                        client().getCapabilities().supportsDocumentSymbol(),
                        "textDocument/documentSymbol",
                        Json.obj("textDocument", Json.obj("uri", uri)));
                return LspConverters.documentSymbols(response);
            } catch (Throwable t) {
                logger().warning("文档符号请求失败: " + t.getMessage());
                return Collections.emptyList();
            }
        }

        @Override
        public List<SymbolInfo> workspaceSymbols(String query,
                                                com.rapid.api.lang.engine.CancellationToken token) {
            if (!ready() || !client().getCapabilities().supportsWorkspaceSymbol()) {
                return Collections.emptyList();
            }
            try {
                Object response = client().request(
                        "workspace/symbol",
                        Json.obj("query", query == null ? "" : query));
                return LspConverters.symbolInfos(response, resolver());
            } catch (Throwable t) {
                logger().warning("工作区符号请求失败: " + t.getMessage());
                return Collections.emptyList();
            }
        }
    }

    /** {@code textDocument/foldingRange}。 */
    public static final class Folding extends LspBaseProvider implements FoldingProvider {

        public Folding(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<FoldingRange> foldingRange(VirtualFile file) {
            String uri = openUri(file);
            if (uri == null) {
                return Collections.emptyList();
            }
            try {
                Object response = client().requestIfSupported(
                        client().getCapabilities().supportsFoldingRange(),
                        "textDocument/foldingRange",
                        Json.obj("textDocument", Json.obj("uri", uri)));
                return LspConverters.foldingRanges(response);
            } catch (Throwable t) {
                logger().warning("折叠范围请求失败: " + t.getMessage());
                return Collections.emptyList();
            }
        }
    }

    /**
     * 诊断读取：{@code textDocument/publishDiagnostics} 的本地缓存出口。
     *
     * <p>LSP 诊断是服务端推送、结绳是编辑器来拉，因此这里只读缓存，不发请求。
     */
    public static final class Diagnostics extends LspBaseProvider implements LintProvider {

        public Diagnostics(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<Diagnostic> lint(VirtualFile file,
                                    com.rapid.api.lang.engine.CancellationToken token) {
            logger().info("LintProvider 被调用");
            if (file == null) {
                return LspDiagnostics.empty();
            }
            String uri = toUri(file);
            if (uri == null) {
                return LspDiagnostics.empty();
            }
            // 如果服务端支持"拉取诊断"（pull diagnostics）也可以在这里补上；
            // 当前先以推送缓存为准，零成本且不会产生额外往返。
            return diagnostics().get(uri);
        }
    }
}
