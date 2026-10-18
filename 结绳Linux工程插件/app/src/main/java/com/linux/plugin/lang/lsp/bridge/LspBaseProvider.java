package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspDiagnostics;
import com.linux.plugin.lang.lsp.LspLanguageContext;
import com.linux.plugin.lang.lsp.VirtualFileResolver;
import com.linux.plugin.lang.lsp.WorkspaceEditApplier;
import com.linux.plugin.lsp.LspClient;
import com.linux.plugin.lsp.LspLogger;
import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.protocol.Uris;
import com.rapid.api.lang.engine.AbstractProvider;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.baseModel.DocumentPosition;
import com.rapid.framework.data.document.Position;
import com.rapid.framework.vfs.VirtualFile;

import java.util.Map;

/**
 * LSP provider 桥接层的公共基类。
 *
 * <p>负责把"结绳的文档模型"翻译成"LSP 的请求参数"，并统一处理：
 * <ul>
 *   <li>连接可用性（未就绪时安静返回空结果，绝不让异常冲进编辑器）；</li>
 *   <li>文档自动打开与正文同步（借 {@link LspClient#ensureOpen}）；</li>
 *   <li>0 基行列换算——LSP 与结绳的 Position 都是 0 基，不需要加减。</li>
 * </ul>
 *
 * <p>{@code AbstractProvider<C extends LanguageContext>} 的泛型参数是语言上下文类型。
 * 桥接层统一用 {@link DefaultLanguageContext}：它能提供"未保存正文"（{@code getDynamicContent}），
 * 这正是 LSP 必须看到的文本。若某个工程有自己的上下文子类，把它传进来即可。
 */
public abstract class LspBaseProvider extends AbstractProvider<DefaultLanguageContext> {

    protected final LspBridgeContext bridge;

    protected LspBaseProvider(DefaultLanguageContext context, LspBridgeContext bridge) {
        super(context);
        this.bridge = bridge;
    }

    /** LSP 客户端（可能尚未就绪）。 */
    protected LspClient client() {
        return bridge.getClient();
    }

    protected LspLogger logger() {
        return bridge.getLogger();
    }

    /** 服务端是否已握手完成。 */
    protected boolean ready() {
        LspClient current = bridge.getClient();
        return current != null && current.isReady();
    }

    protected LspDiagnostics diagnostics() {
        return bridge.getDiagnostics();
    }

    /** URI → 结绳虚拟文件解析器。 */
    protected VirtualFileResolver resolver() {
        return bridge.getResolver();
    }

    /** LSP workspaceEdit 落地器。 */
    protected WorkspaceEditApplier applier() {
        return bridge.getApplier();
    }

    /**
     * 确保文档已在服务端打开，并返回其 URI。
     *
     * @return 文件 URI；连接未就绪或取不到正文时返回 null
     */
    protected String openUri(VirtualFile file) {
        return file == null ? null : openUri(toUri(file));
    }

    /** 确保指定 URI 的文档已在服务端打开。 */
    protected String openUri(String uri) {
        if (uri == null || !ready()) {
            return null;
        }
        String normalized = Uris.normalize(uri);
        // 语言标识交给语言上下文决议（Go 的 go.mod 不能按扩展名推）。
        return client().ensureOpen(normalized, languageIdFor(normalized)) == null ? null : normalized;
    }

    /**
     * 决定发给服务端的 LSP {@code languageId}。
     *
     * <p>语言上下文实现 {@link LspLanguageContext} 时以它为准，
     * 否则按扩展名推断。
     */
    protected String languageIdFor(String uri) {
        try {
            if (context instanceof LspLanguageContext) {
                String fromContext = ((LspLanguageContext) context).languageIdFor(uri);
                if (fromContext != null && !fromContext.isEmpty()) {
                    return fromContext;
                }
            }
        } catch (Throwable ignore) {
            // 上下文实现有异常时退回扩展名推断，不能因此让补全失败。
        }
        return Uris.languageIdOf(uri);
    }

    /** 虚拟文件 → {@code file://} URI。 */
    protected static String toUri(VirtualFile file) {
        if (file == null) {
            return null;
        }
        try {
            java.net.URI uri = file.asURI();
            if (uri != null) {
                return Uris.normalize(uri.toString());
            }
        } catch (Throwable ignore) {
            // 拿不到 URI 就退回按路径拼。
        }
        return Uris.fromPath(file.getPath());
    }

    /** 结绳 {@code Position} → LSP Position 对象。 */
    protected static Map<String, Object> lspPosition(Position position) {
        int line = position == null ? 0 : Math.max(0, position.line);
        int character = position == null ? 0 : Math.max(0, position.character);
        return Json.obj("line", Integer.valueOf(line), "character", Integer.valueOf(character));
    }

    /** LSP 的 {@code TextDocumentPositionParams}：{@code textDocument.uri} + {@code position}。 */
    protected Map<String, Object> textDocumentPosition(String uri, Position position) {
        return Json.obj(
                "textDocument", Json.obj("uri", uri),
                "position", lspPosition(position));
    }

    /** 从 {@link DocumentPosition} 安全取位置。 */
    protected static Position positionOf(DocumentPosition documentPosition) {
        return documentPosition == null ? Position.NONE : documentPosition.position;
    }
}
