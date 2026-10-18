package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspContentProviders;
import com.linux.plugin.lang.lsp.LspDiagnostics;
import com.linux.plugin.lang.lsp.VirtualFileResolver;
import com.linux.plugin.lang.lsp.WorkspaceEditApplier;
import com.linux.plugin.lsp.DocumentContentProvider;
import com.linux.plugin.lsp.LspClient;
import com.linux.plugin.lsp.LspLogger;
import com.rapid.api.lang.engine.DefaultLanguageContext;

import java.io.File;

/**
 * 所有 LSP provider 共享的运行时上下文。
 *
 * <p>一个"语言引擎实例 = 一份桥接上下文"：持有连接、诊断缓存、
 * 文件解析器与落地器，避免每个 provider 各持一份造成状态撕裂。
 *
 * <p>{@link LspClient} 允许延后注入（{@code setClient}）：
 * 语言引擎往往在语言工厂阶段就被构造出来，但服务端进程要到工程打开后才拉起。
 * 在客户端就绪前，所有 provider 会安静地返回空结果。
 */
public final class LspBridgeContext {

    private final LspLogger logger;
    private final LspDiagnostics diagnostics;
    private final VirtualFileResolver resolver;
    private final WorkspaceEditApplier applier;
    private final Object lock = new Object();

    private volatile LspClient client;
    private volatile DefaultLanguageContext languageContext;
    private volatile DocumentContentProvider contentProvider;
    private volatile File workspaceDirectory;

    public LspBridgeContext(LspLogger logger) {
        this.logger = logger == null ? LspLogger.DEFAULT : logger;
        this.diagnostics = new LspDiagnostics(this.logger);
        this.resolver = LspContentProviders.resolver(this.logger);
        this.applier = new WorkspaceEditApplier(this.resolver);
    }

    public LspLogger getLogger() {
        return logger;
    }

    public LspDiagnostics getDiagnostics() {
        return diagnostics;
    }

    public VirtualFileResolver getResolver() {
        return resolver;
    }

    public WorkspaceEditApplier getApplier() {
        return applier;
    }

    public LspClient getClient() {
        return client;
    }

    /** 注入（或替换）LSP 客户端。替换时同步更新其正文来源与通知出口。 */
    public void setClient(LspClient value) {
        synchronized (lock) {
            this.client = value;
            if (value != null) {
                DocumentContentProvider provider = contentProvider;
                if (provider != null) {
                    value.setContentProvider(provider);
                }
                value.setNotificationListener(diagnostics);
            }
        }
    }

    public DefaultLanguageContext getLanguageContext() {
        return languageContext;
    }

    /**
     * 绑定结绳语言上下文。
     *
     * <p>绑定后自动把"未保存正文"的来源接到 LSP 上，
     * 这样服务端始终看到编辑器里的最新文本。
     */
    public void setLanguageContext(DefaultLanguageContext context) {
        this.languageContext = context;
        this.contentProvider = LspContentProviders.fromContext(context, logger);
        LspClient current = client;
        if (current != null) {
            current.setContentProvider(contentProvider);
        }
    }

    public DocumentContentProvider getContentProvider() {
        return contentProvider;
    }

    public File getWorkspaceDirectory() {
        return workspaceDirectory;
    }

    /** 工程根目录：服务端 initialize 的 workspace，也是搜索路径的基准。 */
    public void setWorkspaceDirectory(File directory) {
        this.workspaceDirectory = directory;
    }
}
