package com.linux.plugin.lsp;

import com.linux.plugin.lsp.protocol.ServerCapabilities;

import java.util.List;

/** 服务端推送诊断 / 状态通知的出口。 */
public interface LspNotificationListener {

    /**
     * 服务端发布了某文件的诊断（{@code textDocument/publishDiagnostics}）。
     *
     * @param uri         文件 URI
     * @param diagnostics 原始诊断数组，解析成诊断对象由桥接层负责
     */
    void onDiagnosticsPublished(String uri, List<Object> diagnostics);

    /** 服务端报告窗口消息（{@code window/showMessage}）。 */
    void onShowMessage(int type, String message);

    /** 服务端通知日志（{@code window/logMessage}）。 */
    void onLogMessage(int type, String message);

    /** 连接建立、断开或重启等生命周期变化。 */
    void onStateChanged(LspClient.State state, ServerCapabilities capabilities);
}
