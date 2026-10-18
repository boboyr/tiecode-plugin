package com.goproject.plugin.lsp;

import com.goproject.plugin.lsp.bridge.GoplsLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.rosemoe.sora.lsp.client.languageserver.ShutdownReason;
import io.github.rosemoe.sora.lsp.client.languageserver.requestmanager.RequestManager;
import io.github.rosemoe.sora.lsp.client.languageserver.wrapper.LanguageServerWrapper;
import io.github.rosemoe.sora.lsp.editor.LspProject;
import io.github.rosemoe.sora.lsp.requests.Timeout;
import io.github.rosemoe.sora.lsp.requests.Timeouts;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * gopls 会话管理器:<b>基于 sora 的 editor-lsp 对接</b>,不再自建协议栈。
 *
 * <h3>为什么是无界面(headless)驱动</h3>
 * editor-lsp 的高层入口是 {@code LspEditor},但它默认把编辑器 UI(悬停窗、内联提示、
 * 诊断渲染)一起接上,要求宿主提供一批 sora 界面类;插件编辑内核是结绳自带编辑器,
 * 拿不到这些类。因此这里只复用 editor-lsp 的<b>连接与协议层</b>:
 * {@link LspProject} + {@link LanguageServerWrapper} + lsp4j 的 {@link RequestManager}。
 * 这正是 editor-lsp 的核心价值(读写成帧、能力协商、请求分发),且完全脱离界面。
 *
 * <h3>gopls 从哪来</h3>
 * 由插件<b>自己启动</b>（见 {@link GoplsServerProcess}）：可执行文件来自 assets 里
 * 自带 gopls 的 Go 工具链，走 app 私有目录的 Unix 域套接字通信，不需要 Termux，
 * 也不需要用户手工起服务端。
 *
 * <h3>延迟连接</h3>
 * 插件加载时不知道工程目录,连接在第一次真正需要(补全)时才建立,
 * 且放在后台线程,避免卡住编辑器的补全线程。
 */
public final class GoplsSession {

    private static volatile GoplsSession instance;

    private final Object lock = new Object();

    private volatile LspProject project;
    private volatile LanguageServerWrapper wrapper;
    private volatile RequestManager requestManager;

    /**
     * 由插件自己拉起的 gopls 进程。
     *
     * <p>gopls 与结绳同 uid，因此可以用 app 私有目录里的 Unix 套接字文件，
     * 不再依赖 Termux 或跨 app 权限。
     */
    private final GoplsServerProcess serverProcess = new GoplsServerProcess();

    private volatile String host = GoplsConfig.DEFAULT_HOST;
    private volatile int port = GoplsConfig.DEFAULT_PORT;
    private volatile File workspace;
    private volatile boolean starting;
    private volatile String lastError;

    /** 已发送 didOpen/didChange 的文档版本号。 */
    private final ConcurrentHashMap<String, Integer> versions = new ConcurrentHashMap<>();

    public static GoplsSession get() {
        GoplsSession local = instance;
        if (local == null) {
            synchronized (GoplsSession.class) {
                local = instance;
                if (local == null) {
                    local = new GoplsSession();
                    instance = local;
                }
            }
        }
        return local;
    }

    private GoplsSession() {
    }

    // ------------------------------------------------------------------
    // 端点与状态
    // ------------------------------------------------------------------

    /** 覆盖 gopls 端点;已连接时先断开,下一次请求按新端点重连。 */
    public void setEndpoint(String host, int port) {
        synchronized (lock) {
            if (host != null && !host.trim().isEmpty()) {
                this.host = host.trim();
            }
            if (port > 0 && port <= 65535) {
                this.port = port;
            }
            teardownLocked();
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** 当前 workspace(含 go.mod 的目录);未连接时为 null。 */
    public File getWorkspace() {
        return workspace;
    }

    /** 最近一次连接失败的原因;成功后清空。 */
    public String getLastError() {
        return lastError;
    }

    /** 就绪判定:requestManager 非空即表示 initialize 已完成。 */
    public boolean isReady() {
        return requestManager != null;
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    /**
     * 确保连接可用。
     *
     * @param fileHint 提示用的文件,用来推断 workspace(优先含 go.mod 的目录)
     * @return true 表示可以发请求了
     */
    public boolean ensureReady(File fileHint) {
        if (isReady()) {
            return true;
        }
        synchronized (lock) {
            if (isReady()) {
                return true;
            }
            File resolved = GoplsConfig.resolveWorkspace(fileHint);
            if (resolved == null && fileHint != null) {
                resolved = fileHint.getParentFile();
            }
            if (wrapper != null && sameFile(workspace, resolved)) {
                return isReady();
            }
            return startLocked(resolved);
        }
    }

    /** 在后台尝试连接,不阻塞调用方(预热用)。 */
    public void warmUp(final File fileHint) {
        if (isReady() || starting) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                ensureReady(fileHint);
            }
        }, "gopls-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean startLocked(File resolvedWorkspace) {
        if (starting) {
            return false;
        }
        starting = true;
        try {
            teardownLocked();
            if (resolvedWorkspace == null) {
                lastError = "无法确定 gopls workspace(未找到 go.mod)";
                return false;
            }

            applyTimeouts();

            // 先确保工具链已解压、gopls 进程已启动并监听套接字。
            // 这一步可能要几秒（首次解压 92MB 的 zip），所以整个 startLocked 必须在后台线程执行。
            if (GoplsConfig.getTransport() != GoplsConfig.Transport.TCP) {
                if (!serverProcess.start(GoplsToolchain.pluginContext(), resolvedWorkspace)) {
                    lastError = "无法启动 gopls 进程: " + serverProcess.describeCommand();
                    GoplsLogger.get().error(lastError);
                    return false;
                }
            }

            LspProject createdProject = new LspProject(resolvedWorkspace.getAbsolutePath());
            GoplsServerDefinition definition =
                    new GoplsServerDefinition(GoplsConfig.LANGUAGE_ID, this);
            createdProject.addServerDefinition(definition);

            LanguageServerWrapper createdWrapper =
                    new LanguageServerWrapper(definition, createdProject);

            // 阻塞式启动:内部建立 Unix 套接字通道并完成 initialize 握手。
            long startedAt = System.currentTimeMillis();
            createdWrapper.start(false);
            long elapsed = System.currentTimeMillis() - startedAt;

            ServerCapabilities capabilities = createdWrapper.getServerCapabilities();
            RequestManager manager = createdWrapper.getRequestManager();
            if (capabilities == null || manager == null) {
                // 自启动模式下失败基本是协议握手问题，日志文件里有 gopls 原始输出。
                lastError = "gopls 初始化失败或超时(" + GoplsConfig.describeTransport()
                        + ")，详见 " + GoplsToolchain.serverLogFile(GoplsToolchain.pluginContext());
                GoplsLogger.get().warning(lastError);
                createdWrapper.stop(true, ShutdownReason.TIMEOUT);
                createdProject.dispose();
                return false;
            }

            this.project = createdProject;
            this.wrapper = createdWrapper;
            this.requestManager = manager;
            this.workspace = resolvedWorkspace;
            this.lastError = null;
            GoplsLogger.get().info("gopls 连接成功: " + GoplsConfig.describeTransport()
                    + " workspace=" + resolvedWorkspace
                    + " 握手耗时=" + elapsed + "ms");
            return true;
        } catch (Throwable t) {
            lastError = describe(t);
            GoplsLogger.get().warning("连接 gopls 失败(" + GoplsConfig.describeTransport()
                    + "): " + lastError);
            teardownLocked();
            return false;
        } finally {
            starting = false;
        }
    }

    private void applyTimeouts() {
        try {
            Timeout.INSTANCE.set(Timeouts.INIT, GoplsConfig.INIT_TIMEOUT_MILLIS);
            Timeout.INSTANCE.set(Timeouts.COMPLETION, GoplsConfig.COMPLETION_TIMEOUT_MILLIS);
        } catch (Throwable ignore) {
            // 超时设置失败不影响基本功能。
        }
    }

    /** 断开连接(工程关闭/切换/插件卸载时调用)。 */
    public void shutdown() {
        synchronized (lock) {
            teardownLocked();
        }
    }

    /** 彻底停止：断开连接并结束 gopls 进程。 */
    public void shutdownProcess() {
        synchronized (lock) {
            teardownLocked();
            serverProcess.stop();
        }
    }

    private void teardownLocked() {
        LanguageServerWrapper currentWrapper = wrapper;
        LspProject currentProject = project;
        wrapper = null;
        requestManager = null;
        project = null;
        workspace = null;
        versions.clear();
        if (currentWrapper != null) {
            try {
                currentWrapper.stop(true, ShutdownReason.MANUAL);
            } catch (Throwable ignore) {
            }
        }
        if (currentProject != null) {
            try {
                currentProject.dispose();
            } catch (Throwable ignore) {
            }
        }
        // 注意：这里<b>不</b>结束 gopls 进程。
        // 进程启动成本高（还要重新索引），而断线重连很常见；
        // 进程由 shutdownProcess() 在插件卸载时统一收尾。
    }

    // ------------------------------------------------------------------
    // 文档同步
    // ------------------------------------------------------------------

    /**
     * 确保某文档已在服务端打开,并把最新正文同步过去。
     *
     * <p>统一用全量同步:服务端声明增量时全量也合法,兼容性最好。
     *
     * @return 成功返回 true
     */
    public boolean ensureOpen(String uri, String content, String languageId) {
        RequestManager manager = requestManager;
        if (manager == null || content == null) {
            return false;
        }
        String key = Uris.normalize(uri);
        Integer current = versions.get(key);
        if (current == null) {
            manager.didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(key, languageId, 1, content)));
            versions.put(key, 1);
            return true;
        }
        manager.didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(key, current + 1),
                Collections.singletonList(new TextDocumentContentChangeEvent(content))));
        versions.put(key, current + 1);
        return true;
    }

    // ------------------------------------------------------------------
    // 补全
    // ------------------------------------------------------------------

    /** 一次补全请求的结果。 */
    public static final class CompletionResult {

        /** lsp4j 的原始补全项。 */
        public final List<CompletionItem> items;
        /** 失败原因,成功为 null。 */
        public final String error;

        CompletionResult(List<CompletionItem> items, String error) {
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

    /**
     * 向 gopls 请求补全。
     *
     * <p>阻塞调用:Sora 的补全线程本身就是工作线程,直接调用是安全的。
     *
     * @param triggerKind 1=手动触发 2=触发字符
     */
    public CompletionResult completion(File file, String content, int line, int column,
                                      char triggerChar, int triggerKind) {
        if (file == null) {
            return new CompletionResult(Collections.<CompletionItem>emptyList(), "没有文件信息");
        }
        if (!ensureReady(file)) {
            String error = lastError;
            return new CompletionResult(Collections.<CompletionItem>emptyList(),
                    error == null ? "gopls 未连接" : error);
        }
        RequestManager manager = requestManager;
        if (manager == null) {
            return new CompletionResult(Collections.<CompletionItem>emptyList(), "gopls 未就绪");
        }

        try {
            String uri = Uris.fromFile(file);
            if (uri == null) {
                return new CompletionResult(Collections.<CompletionItem>emptyList(), "文件 URI 无效");
            }
            ensureOpen(uri, content, languageIdFor(uri));

            CompletionContext context = new CompletionContext(
                    triggerKind == 2 ? CompletionTriggerKind.TriggerCharacter
                            : CompletionTriggerKind.Invoked);
            if (triggerChar != '\0') {
                context.setTriggerCharacter(String.valueOf(triggerChar));
            }

            CompletionParams params = new CompletionParams(
                    new TextDocumentIdentifier(Uris.normalize(uri)),
                    new Position(Math.max(0, line), Math.max(0, column)),
                    context);

            CompletableFuture<Either<List<CompletionItem>, CompletionList>> future =
                    manager.completion(params);
            if (future == null) {
                return new CompletionResult(Collections.<CompletionItem>emptyList(),
                        "gopls 未声明补全能力");
            }

            Either<List<CompletionItem>, CompletionList> either =
                    future.get(GoplsConfig.COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            List<CompletionItem> items = new ArrayList<>();
            if (either != null) {
                if (either.isLeft() && either.getLeft() != null) {
                    items.addAll(either.getLeft());
                } else if (either.isRight() && either.getRight() != null
                        && either.getRight().getItems() != null) {
                    items.addAll(either.getRight().getItems());
                }
            }
            return new CompletionResult(items, null);
        } catch (Throwable t) {
            return new CompletionResult(Collections.<CompletionItem>emptyList(), describe(t));
        }
    }

    public RequestManager getRequestManager() {
        return requestManager;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 语言标识:go.mod / go.sum / go.work 有专门的 languageId。 */
    public static String languageIdFor(String uri) {
        String fromConfig = GoplsConfig.languageIdFor(uri);
        return fromConfig != null ? fromConfig : Uris.languageIdOf(uri);
    }

    /** 该文件是否值得发给 gopls。 */
    public static boolean supports(File file) {
        return file != null && GoplsConfig.isGoFile(file.getName());
    }

    private static boolean sameFile(File left, File right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getAbsolutePath().equals(right.getAbsolutePath());
    }

    private static String describe(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isEmpty()) {
            message = cursor.getClass().getSimpleName();
        }
        return message;
    }
}
