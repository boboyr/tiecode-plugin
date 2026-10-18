package com.linux.plugin.lsp;

import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.protocol.JsonRpcConnection;
import com.linux.plugin.lsp.protocol.LspServerConfig;
import com.linux.plugin.lsp.protocol.ServerCapabilities;
import com.linux.plugin.lsp.protocol.TransportFactory;
import com.linux.plugin.lsp.protocol.Uris;
import com.linux.plugin.lsp.transport.Transport;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP 客户端：负责协议生命周期与能力协商，向上暴露"已初始化好的连接"。
 *
 * <p>分层：{@code Transport}（字节流）→ {@code JsonRpcConnection}（请求/响应）
 * → 本类（LSP 语义）→ {@code com.linux.plugin.lang.lsp} 里的 provider 桥接。
 *
 * <p>线程模型：所有 {@code request*} 方法可被任意线程调用并阻塞等待；
 * 收到的通知由单条读线程分发。上行请求串行化写入，不会交错。
 *
 * <p>典型用法：
 * <pre>
 * LspClient client = new LspClient(config, logger);
 * client.setContentProvider(uri -&gt; 编辑器当前文本);
 * client.setNotificationListener(this);
 * client.start(new File("/path/to/project"));   // 完成 initialize 握手
 * client.didChangeDocument(uri, "新内容");       // 同步编辑
 * </pre>
 */
public final class LspClient {

    /** 生命周期状态。 */
    public enum State {
        /** 尚未启动。 */
        STOPPED,
        /** 正在拉起服务端 / 握手中。 */
        STARTING,
        /** 已初始化完成，可以发请求。 */
        READY,
        /** 连接断开（可能是服务端崩溃）。 */
        FAILED
    }

    /** 协议层错误（服务端返回 error、超时或连接不可用）。 */
    public static class LspException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String rpcMethod;

        public LspException(String message, String rpcMethod) {
            super(message);
            this.rpcMethod = rpcMethod;
        }

        public LspException(String message, String rpcMethod, Throwable cause) {
            super(message, cause);
            this.rpcMethod = rpcMethod;
        }

        public String getRpcMethod() {
            return rpcMethod;
        }
    }

    /** 已打开文档的同步状态。 */
    private static final class OpenDocument {

        final Object lock = new Object();
        int version;
        String content;
        String languageId;
        boolean opened;
    }

    private final LspServerConfig config;
    private final LspLogger logger;
    private final TransportFactory transportFactory;
    private final ConcurrentHashMap<String, OpenDocument> documents = new ConcurrentHashMap<>();

    private volatile JsonRpcConnection connection;
    private volatile ServerCapabilities capabilities = new ServerCapabilities(null);
    private volatile State state = State.STOPPED;
    private volatile File workspaceDirectory;
    private volatile String rootUri;

    private volatile DocumentContentProvider contentProvider;
    private volatile LspNotificationListener notificationListener;
    private volatile boolean shuttingDown;

    public LspClient(LspServerConfig config, LspLogger logger) {
        if (config == null) {
            throw new IllegalArgumentException("服务端配置不能为空");
        }
        this.config = config;
        this.logger = logger == null ? LspLogger.DEFAULT : logger;
        this.transportFactory = new TransportFactory() {
            @Override
            public com.linux.plugin.lsp.transport.Transport create() {
                return LspClient.this.config.createTransport();
            }
        };
    }

    public void setContentProvider(DocumentContentProvider provider) {
        this.contentProvider = provider;
    }

    public void setNotificationListener(LspNotificationListener listener) {
        this.notificationListener = listener;
    }

    public LspServerConfig getConfig() {
        return config;
    }

    public State getState() {
        return state;
    }

    public ServerCapabilities getCapabilities() {
        return capabilities;
    }

    public boolean isReady() {
        return state == State.READY && connection != null && connection.isRunning();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 拉起服务端并完成 {@code initialize} 握手。
     *
     * @param workspaceDirectory 工程根目录，作为 LSP 的 workspace
     * @throws LspException 启动或握手失败
     */
    public void start(File workspaceDirectory) throws LspException {
        this.workspaceDirectory = workspaceDirectory;
        this.rootUri = workspaceDirectory == null ? null : Uris.fromFile(workspaceDirectory);
        this.shuttingDown = false;
        transitionTo(State.STARTING);

        JsonRpcConnection created = new JsonRpcConnection(transportFactory, logger);
        created.setNotificationHandler(new JsonRpcConnection.NotificationHandler() {
            @Override
            public void handle(String method, Map<String, Object> params) {
                handleNotification(method, params);
            }
        });
        created.setRequestHandler(new JsonRpcConnection.RequestHandler() {
            @Override
            public Object handle(String method, Map<String, Object> params) {
                return handleServerRequest(method, params);
            }
        });
        created.setServerErrorListener(new Transport.LogListener() {
            @Override
            public void onLog(String line) {
                logger.info("[server] " + line);
            }
        });
        created.setDisconnectHandler(new Runnable() {
            @Override
            public void run() {
                if (!shuttingDown) {
                    transitionTo(State.FAILED);
                }
            }
        });

        try {
            created.start();
        } catch (Exception e) {
            transitionTo(State.FAILED);
            throw new LspException("无法建立 LSP 通道(" + config + "): " + e.getMessage(), "start", e);
        }

        connection = created;
        initialize();
        transitionTo(State.READY);
    }

    /** 优雅关闭：{@code shutdown} 请求 → {@code exit} 通知 → 关通道。 */
    public void shutdown() {
        shuttingDown = true;
        JsonRpcConnection current = connection;
        connection = null;
        if (current != null) {
            try {
                if (current.isRunning()) {
                    current.request("shutdown", null, 3_000L);
                }
            } catch (Throwable ignore) {
                // 服务端无响应直接退出也可接受。
            }
            try {
                current.notify("exit", null);
            } catch (Throwable ignore) {
            }
            current.close();
        }
        documents.clear();
        transitionTo(State.STOPPED);
    }

    /** 强制断开，不做协议级告别（服务端已死时用）。 */
    public void kill() {
        shuttingDown = true;
        JsonRpcConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
        }
        documents.clear();
        transitionTo(State.STOPPED);
    }

    // ------------------------------------------------------------------
    // 请求入口（供 provider 桥接层调用）
    // ------------------------------------------------------------------

    /**
     * 发送请求并返回原始 JSON 结果。
     *
     * @throws LspException 未就绪、超时或服务端报错
     */
    public Object request(String method, Object params) throws LspException {
        JsonRpcConnection current = connection;
        if (current == null || !current.isRunning()) {
            throw new LspException("LSP 连接不可用: " + config.getId(), method);
        }
        try {
            return current.request(method, params, config.getRequestTimeoutMillis());
        } catch (JsonRpcConnection.RpcException e) {
            throw new LspException(e.getMessage(), method, e);
        }
    }

    /** 发送通知；连接不可用时静默忽略（通知本来就不保证送达）。 */
    public void notify(String method, Object params) {
        JsonRpcConnection current = connection;
        if (current == null || !current.isRunning()) {
            return;
        }
        try {
            current.notify(method, params);
        } catch (RuntimeException e) {
            logger.warning("发送通知失败 " + method + ": " + e.getMessage());
        }
    }

    /**
     * 按需发送请求：服务端未声明对应能力时返回 null，避免无意义的往返。
     */
    public Object requestIfSupported(boolean supported, String method, Object params) throws LspException {
        if (!supported) {
            return null;
        }
        return request(method, params);
    }

    // ------------------------------------------------------------------
    // 文档同步
    // ------------------------------------------------------------------

    /**
     * 确保文档已在服务端打开并返回当前正文。
     *
     * @return 正文；取不到时返回 null（调用方应直接放弃本次请求）
     */
    public String ensureOpen(String uri) {
        return ensureOpen(uri, null);
    }

    /**
     * 确保文档已在服务端打开并返回当前正文。
     *
     * @param languageId 首次 didOpen 时发给服务端的语言标识；
     *                   传 null 表示按扩展名自动推断。只有"第一次打开"会用到它，
     *                   因此调用方每次多传一个值也没有额外成本。
     * @return 正文；取不到时返回 null（调用方应直接放弃本次请求）
     */
    public String ensureOpen(String uri, String languageId) {
        if (uri == null) {
            return null;
        }
        String normalized = Uris.normalize(uri);
        OpenDocument document = documentOf(normalized);
        String content = readContent(normalized);
        if (content == null) {
            return null;
        }
        synchronized (document.lock) {
            if (!document.opened) {
                document.content = content;
                document.version = 1;
                // 语言标识由调用方决定：Go 的 .mod/.sum 按扩展名推会推错。
                document.languageId = languageId == null || languageId.isEmpty()
                        ? Uris.languageIdOf(normalized)
                        : languageId;
                document.opened = true;
                notify("textDocument/didOpen", Json.obj("textDocument", Json.obj(
                        "uri", normalized,
                        "languageId", document.languageId,
                        "version", Integer.valueOf(document.version),
                        "text", content)));
            } else if (!content.equals(document.content)) {
                // 正文在服务端不知情的情况下变了（例如未经 didChange 的外部改动），
                // 这里补一次全量同步，宁可多发也不能让服务端用旧文本算结果。
                document.content = content;
                document.version++;
                notify("textDocument/didChange", Json.obj(
                        "textDocument", versionedIdentifier(normalized, document.version),
                        "contentChanges", Json.arr(Json.obj("text", content))));
            }
            return document.content;
        }
    }

    /**
     * 确保文档已打开，只关心成功与否。
     *
     * <p>给不需要正文的调用方（代码操作、符号等）用。
     */
    public boolean ensureDocument(String uri) {
        return ensureOpen(uri) != null;
    }

    /**
     * 编辑器内容变更（{@code textDocument/didChange}）。
     *
     * <p>先走 {@link #ensureOpen} 而不是直查 map：编辑器可能在服务端就绪之前
     * 就已经"打开"了这个文档，此时该文档既没被人请求过、也没发过 didOpen。
     * 直查会静默丢掉这次变更，让服务端一直拿着旧文本算结果。
     */
    public void didChangeDocument(String uri, String newContent) {
        if (uri == null || newContent == null) {
            return;
        }
        String normalized = Uris.normalize(uri);
        OpenDocument document = documentOf(normalized);
        // 确保已打开（必要时会补一次全量同步），再叠加本次变更。
        ensureOpen(normalized);
        synchronized (document.lock) {
            if (!document.opened) {
                return;
            }
            document.content = newContent;
            document.version++;
            notify("textDocument/didChange", Json.obj(
                    "textDocument", versionedIdentifier(normalized, document.version),
                    // 统一用全量同步：服务端声明增量时全量也合法，兼容性最好。
                    "contentChanges", Json.arr(Json.obj("text", newContent))));
        }
    }

    /** 文档保存（{@code textDocument/didSave}）。 */
    public void didSaveDocument(String uri) {
        if (uri == null || !isReady()) {
            return;
        }
        String normalized = Uris.normalize(uri);
        String content = ensureOpen(normalized);
        OpenDocument document = documents.get(normalized);
        if (document == null || !document.opened) {
            return;
        }
        Map<String, Object> params = Json.obj("textDocument", Json.obj("uri", normalized));
        if (content != null) {
            // 带上全文：部分服务端要到 didSave 才真正重建索引，
            // 没有文本时它们只能用自己缓存的旧版本。
            params.put("text", content);
        }
        notify("textDocument/didSave", params);
    }

    /** 文档关闭（{@code textDocument/didClose}）。 */
    public void didCloseDocument(String uri) {
        if (uri == null) {
            return;
        }
        String normalized = Uris.normalize(uri);
        OpenDocument document = documents.remove(normalized);
        if (document == null || !document.opened) {
            return;
        }
        notify("textDocument/didClose", Json.obj("textDocument", Json.obj("uri", normalized)));
    }

    /** 工作区新增文件（{@code workspace/didChangeWatchedFiles} Created）。 */
    public void didCreateFile(String uri) {
        watchedFileChanged(uri, 1);
    }

    /** 工作区删除文件（Deleted）。 */
    public void didDeleteFile(String uri) {
        watchedFileChanged(uri, 3);
        didCloseDocument(uri);
    }

    /** 工作区重命名文件（Deleted 旧的 + Created 新的）。 */
    public void didRenameFile(String oldUri, String newUri) {
        didCloseDocument(oldUri);
        watchedFileChanged(oldUri, 3);
        watchedFileChanged(newUri, 1);
    }

    private void watchedFileChanged(String uri, int changeType) {
        if (uri == null) {
            return;
        }
        notify("workspace/didChangeWatchedFiles", Json.obj("changes", Json.arr(Json.obj(
                "uri", Uris.normalize(uri),
                "type", Integer.valueOf(changeType)))));
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void initialize() throws LspException {
        String name = "dsh-lsp";
        Map<String, Object> params = Json.obj(
                "processId", null,
                "clientInfo", Json.obj("name", name, "version", "1.0"),
                "capabilities", clientCapabilities(),
                "trace", "off");

        if (rootUri != null) {
            params.put("rootUri", rootUri);
            // 服务端普遍兼容 rootPath，保留以防老服务端只认它。
            if (workspaceDirectory != null) {
                params.put("rootPath", workspaceDirectory.getAbsolutePath());
            }
            params.put("workspaceFolders", Json.arr(Json.obj(
                    "uri", rootUri,
                    "name", workspaceDirectory == null ? "" : workspaceDirectory.getName())));
        } else {
            params.put("rootUri", null);
            params.put("workspaceFolders", null);
        }
        if (config.getInitializationOptions() != null) {
            params.put("initializationOptions", config.getInitializationOptions());
        }

        Object result = request("initialize", params);
        Map<String, Object> resultObject = Json.object(result);
        capabilities = new ServerCapabilities(Json.object(resultObject, "capabilities"));
        logger.info("LSP 已就绪: " + config.getId() + " → " + capabilities);

        notify("initialized", Json.obj());
        if (notificationListener != null) {
            notificationListener.onStateChanged(State.STARTING, capabilities);
        }
    }

    /**
     * 客户端能力声明：只开真正会用到的，避免服务端发一堆用不上的推送。
     *
     * <p>刻意<b>不</b>声明 {@code workspace.fileOperations}：LSP 3.16 规范把
     * {@code didCreate} 等定义为 {@code FileOperationRegistrationOptions}（对象），
     * 而 gopls 的 {@code FileOperationClientCapabilities} 把它简化成了 {@code bool}——
     * 按规范发对象会被 gopls 直接判为 JSON 解析错误，整个 {@code initialize} 失败。
     * 我们本来就用 {@code workspace/didChangeWatchedFiles} 通知文件增删改，
     * 它不依赖任何能力协商，所以不声明反而更稳。
     */
    private Map<String, Object> clientCapabilities() {
        return Json.obj(
                "workspace", Json.obj(
                        "applyEdit", Boolean.TRUE,
                        "workspaceEdit", Json.obj(
                                "documentChanges", Boolean.FALSE,
                                "resourceOperations", Json.arr("create", "rename", "delete")),
                        "didChangeWatchedFiles", Json.obj("dynamicRegistration", Boolean.FALSE),
                        "symbol", Json.obj("symbolKind", Json.obj("valueSet", symbolKinds())),
                        // 声明"支持工作区文件夹"，服务端才会按多根处理。
                        "workspaceFolders", Boolean.TRUE,
                        "configuration", Boolean.TRUE),
                "textDocument", Json.obj(
                        "synchronization", Json.obj(
                                "dynamicRegistration", Boolean.FALSE,
                                "willSave", Boolean.FALSE,
                                "willSaveWaitUntil", Boolean.FALSE,
                                "didSave", Boolean.TRUE),
                        "completion", Json.obj(
                                "dynamicRegistration", Boolean.FALSE,
                                "snippetSupport", Boolean.TRUE,
                                "documentationFormat", Json.arr("markdown", "plaintext"),
                                "completionItem", Json.obj(
                                        "snippetSupport", Boolean.TRUE,
                                        // 结绳的 CompletionItem 有 deprecated 字段，声明它才能收到该信息。
                                        "deprecatedSupport", Boolean.TRUE,
                                        "documentationFormat", Json.arr("markdown", "plaintext"),
                                        // 我们只认 TextEdit 形态，不处理 insert/replace 双范围。
                                        "insertReplaceSupport", Boolean.FALSE,
                                        // 1=AsIs、2=AdjustIndentation，两者都能直接落到文本上。
                                        "insertTextModeSupport", Json.obj("valueSet", Json.arr(
                                                Integer.valueOf(1), Integer.valueOf(2))),
                                        "resolveSupport", Json.obj("properties", Json.arr(
                                                "documentation", "detail", "additionalTextEdits")))),
                        "hover", Json.obj(
                                "dynamicRegistration", Boolean.FALSE,
                                "contentFormat", Json.arr("markdown", "plaintext")),
                        "signatureHelp", Json.obj(
                                "dynamicRegistration", Boolean.FALSE,
                                "signatureInformation", Json.obj(
                                        "documentationFormat", Json.arr("markdown", "plaintext"),
                                        "parameterInformation", Json.obj("labelOffsetSupport", Boolean.TRUE))),
                        "definition", Json.obj("dynamicRegistration", Boolean.FALSE, "linkSupport", Boolean.TRUE),
                        // 结绳没有"声明跳转/类型定义"的独立入口，声明为不支持，
                        // 免得服务端期待我们发这两类请求。
                        "declaration", Json.obj("dynamicRegistration", Boolean.FALSE, "linkSupport", Boolean.FALSE),
                        "typeDefinition", Json.obj("dynamicRegistration", Boolean.FALSE, "linkSupport", Boolean.FALSE),
                        "implementation", Json.obj("dynamicRegistration", Boolean.FALSE, "linkSupport", Boolean.TRUE),
                        "references", Json.obj("dynamicRegistration", Boolean.FALSE),
                        "documentHighlight", Json.obj("dynamicRegistration", Boolean.FALSE),
                        "documentSymbol", Json.obj(
                                "dynamicRegistration", Boolean.FALSE,
                                "hierarchicalDocumentSymbolSupport", Boolean.TRUE,
                                "symbolKind", Json.obj("valueSet", symbolKinds())),
                        "codeAction", Json.obj(
                                "dynamicRegistration", Boolean.FALSE,
                                "codeActionLiteralSupport", Json.obj(
                                        "codeActionKind", Json.obj("valueSet", Json.arr(
                                                "quickfix", "refactor", "refactor.extract",
                                                "refactor.inline", "refactor.rewrite",
                                                "source", "source.organizeImports"))),
                                "isPreferredSupport", Boolean.TRUE),
                        "formatting", Json.obj("dynamicRegistration", Boolean.FALSE),
                        "rangeFormatting", Json.obj("dynamicRegistration", Boolean.FALSE),
                        "rename", Json.obj("dynamicRegistration", Boolean.FALSE, "prepareSupport", Boolean.TRUE),
                        "foldingRange", Json.obj("dynamicRegistration", Boolean.FALSE),
                        "codeLens", Json.obj("dynamicRegistration", Boolean.FALSE),
                        "publishDiagnostics", Json.obj(
                                "relatedInformation", Boolean.TRUE,
                                "tagSupport", Json.obj("valueSet", Json.arr(
                                        Integer.valueOf(1), Integer.valueOf(2)))),
                        "semanticTokens", Json.obj("dynamicRegistration", Boolean.FALSE)),
                "window", Json.obj("workDoneProgress", Boolean.TRUE));
    }

    /** LSP SymbolKind 全集，声明出来让服务端放心发各种符号。 */
    private static List<Object> symbolKinds() {
        List<Object> kinds = new ArrayList<>(26);
        for (int i = 1; i <= 26; i++) {
            kinds.add(Integer.valueOf(i));
        }
        return kinds;
    }

    private void handleNotification(String method, Map<String, Object> params) {
        LspNotificationListener listener = notificationListener;
        if ("textDocument/publishDiagnostics".equals(method)) {
            String uri = Json.string(params, "uri", null);
            List<Object> diagnostics = Json.array(params, "diagnostics");
            if (listener != null && uri != null) {
                listener.onDiagnosticsPublished(Uris.normalize(uri), diagnostics);
            }
            return;
        }
        if ("window/showMessage".equals(method)) {
            if (listener != null) {
                listener.onShowMessage(Json.integer(params, "type", 3), Json.string(params, "message", ""));
            }
            return;
        }
        if ("window/logMessage".equals(method)) {
            String text = Json.string(params, "message", "");
            if (listener != null) {
                listener.onLogMessage(Json.integer(params, "type", 4), text);
            } else {
                logger.info("[server] " + text);
            }
            return;
        }
        if ("telemetry/event".equals(method) || "$/progress".equals(method)) {
            // 静默忽略：这类推送与语言服务结果无关。
            return;
        }
        logger.info("忽略未处理的通知: " + method);
    }

    /**
     * 处理服务端反向请求。
     *
     * <p>只实现"必须回一个合理值，否则服务端会卡住"的那几个；
     * 不支持的统一回 {@code null}（JSON-RPC 语义上是成功但无结果），
     * 比报 error 更不容易让服务端中断。
     */
    private Object handleServerRequest(String method, Map<String, Object> params) {
        if ("workspace/configuration".equals(method)) {
            List<Object> items = Json.array(params, "items");
            List<Object> result = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                result.add(null);
            }
            return result;
        }
        if ("client/registerCapability".equals(method)) {
            // 静态声明已经覆盖，动态注册一律确认但不真正实现。
            logger.info("服务端请求动态注册能力，已确认（静态能力已声明）");
            return null;
        }
        if ("client/unregisterCapability".equals(method)) {
            return null;
        }
        if ("window/workDoneProgress/create".equals(method)) {
            return null;
        }
        if ("workspace/workspaceFolders".equals(method)) {
            if (rootUri == null) {
                return null;
            }
            return Json.arr(Json.obj(
                    "uri", rootUri,
                    "name", workspaceDirectory == null ? "" : workspaceDirectory.getName()));
        }
        if ("workspace/applyEdit".equals(method)) {
            // 服务端要改文件：桥接层通过 applyEdit 单独走编辑器，不在这里静默落盘。
            logger.warning("服务端请求 workspace/applyEdit，当前未接管，已拒绝");
            throw new UnsupportedOperationException("workspace/applyEdit 未启用");
        }
        logger.info("拒绝未实现的服务端请求: " + method);
        throw new UnsupportedOperationException("不支持的方法: " + method);
    }

    private OpenDocument documentOf(String normalizedUri) {
        OpenDocument existing = documents.get(normalizedUri);
        if (existing != null) {
            return existing;
        }
        OpenDocument created = new OpenDocument();
        OpenDocument raced = documents.putIfAbsent(normalizedUri, created);
        return raced == null ? created : raced;
    }

    private Map<String, Object> versionedIdentifier(String uri, int version) {
        return Json.obj("uri", uri, "version", Integer.valueOf(version));
    }

    /** 从内容提供者取正文；没有提供者则退回直接读文件。 */
    private String readContent(String uri) {
        DocumentContentProvider provider = contentProvider;
        if (provider != null) {
            try {
                String content = provider.getContent(uri);
                if (content != null) {
                    return content;
                }
            } catch (Throwable t) {
                logger.warning("内容提供者读取失败(" + uri + "): " + t);
            }
        }
        File file = Uris.toFile(uri);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            byte[] bytes = readAllBytes(file);
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            logger.warning("读取文件失败(" + file + "): " + e.getMessage());
            return null;
        }
    }

    private static byte[] readAllBytes(File file) throws java.io.IOException {
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            long length = file.length();
            if (length > Integer.MAX_VALUE) {
                throw new java.io.IOException("文件过大: " + length);
            }
            byte[] buffer = new byte[(int) length];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read == buffer.length) {
                return buffer;
            }
            byte[] trimmed = new byte[read];
            System.arraycopy(buffer, 0, trimmed, 0, read);
            return trimmed;
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
    }

    private void transitionTo(State next) {
        State previous = state;
        state = next;
        if (previous != next) {
            logger.info("LSP 状态: " + previous + " → " + next + " (" + config.getId() + ")");
        }
        LspNotificationListener listener = notificationListener;
        if (listener != null && next != State.STARTING) {
            listener.onStateChanged(next, capabilities);
        }
    }

    /** 供诊断缓存按 URI 查询时复用：归一化 URI。 */
    public static String normalizeUri(String uri) {
        return Uris.normalize(uri);
    }

    /** 只读视图：当前已跟踪的文档 URI。 */
    public List<String> getOpenDocumentUris() {
        return Collections.unmodifiableList(new ArrayList<>(documents.keySet()));
    }
}
