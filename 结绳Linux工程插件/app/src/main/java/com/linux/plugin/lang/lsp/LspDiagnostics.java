package com.linux.plugin.lang.lsp;

import com.linux.plugin.lsp.LspClient;
import com.linux.plugin.lsp.LspLogger;
import com.linux.plugin.lsp.LspNotificationListener;
import com.linux.plugin.lsp.protocol.ServerCapabilities;
import com.linux.plugin.lsp.protocol.Uris;
import com.rapid.api.lang.engine.lint.Diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端推送诊断的本地缓存。
 *
 * <p>LSP 是"服务端主动推"（{@code publishDiagnostics}），而结绳的
 * {@code LintProvider} 是"编辑器来拉"，两者节奏不同，因此需要这一层：
 * 推送进来先存好，拉取时直接返回。
 *
 * <p>同时承担断线清理：连接不可用时旧诊断必须丢掉，
 * 否则编辑器会一直显示已经不存在的错误。
 */
public final class LspDiagnostics implements LspNotificationListener {

    private final LspLogger logger;
    private final ConcurrentHashMap<String, List<Diagnostic>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Object>> rawCache = new ConcurrentHashMap<>();

    /** 诊断变化通知（可选），用于让编辑器刷新问题列表。 */
    private volatile Runnable changeListener;

    public LspDiagnostics(LspLogger logger) {
        this.logger = logger == null ? LspLogger.DEFAULT : logger;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** 读取某文件的诊断；没有推送过时返回空列表。 */
    public List<Diagnostic> get(String uri) {
        List<Diagnostic> diagnostics = cache.get(LspClient.normalizeUri(uri));
        return diagnostics == null ? Collections.<Diagnostic>emptyList() : diagnostics;
    }

    /** 当前有诊断的文件 URI 快照。 */
    public List<String> uris() {
        return new ArrayList<>(cache.keySet());
    }

    /** 清空全部缓存（断线/工程切换时调用）。 */
    public void clear() {
        cache.clear();
        rawCache.clear();
        notifyChanged();
    }

    /** 丢弃某个文件的诊断（文件关闭时调用）。 */
    public void remove(String uri) {
        String key = LspClient.normalizeUri(uri);
        if (cache.remove(key) != null || rawCache.remove(key) != null) {
            notifyChanged();
        }
    }

    @Override
    public void onDiagnosticsPublished(String uri, List<Object> diagnostics) {
        if (uri == null) {
            return;
        }
        String key = LspClient.normalizeUri(uri);
        if (diagnostics == null || diagnostics.isEmpty()) {
            // 空数组表示"这个文件现在没问题"，必须覆盖旧结果而不是忽略。
            boolean had = cache.remove(key) != null;
            rawCache.remove(key);
            if (had) {
                notifyChanged();
            }
            return;
        }
        List<Diagnostic> converted = LspConverters.diagnostics(diagnostics);
        cache.put(key, Collections.unmodifiableList(converted));
        rawCache.put(key, new ArrayList<Object>(diagnostics));
        notifyChanged();
    }

    @Override
    public void onShowMessage(int type, String message) {
        logger.info("服务端消息(" + type + "): " + message);
    }

    @Override
    public void onLogMessage(int type, String message) {
        logger.info("服务端日志(" + type + "): " + message);
    }

    @Override
    public void onStateChanged(LspClient.State state, ServerCapabilities capabilities) {
        if (state == LspClient.State.FAILED || state == LspClient.State.STOPPED) {
            // 连接没了，之前的诊断不再可信。
            clear();
        }
    }

    /**
     * 供调试：服务端原样推送的诊断 JSON。
     *
     * @return 该文件尚未推送过时返回 null
     */
    public List<Object> rawDiagnostics(String uri) {
        List<Object> raw = rawCache.get(LspClient.normalizeUri(uri));
        return raw == null ? null : java.util.Collections.unmodifiableList(raw);
    }

    private void notifyChanged() {
        Runnable listener = changeListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Throwable t) {
            logger.warning("诊断变化回调失败: " + t);
        }
    }

    /** 便捷方法：URI 是否已有诊断。 */
    public boolean hasDiagnostics(String uri) {
        return cache.containsKey(LspClient.normalizeUri(uri));
    }

    /** 供 provider 一次性构造空结果用。 */
    public static List<Diagnostic> empty() {
        return Collections.emptyList();
    }

    /** 归一化工具透出，避免各 provider 重复 import。 */
    public static String normalize(String uri) {
        return Uris.normalize(uri);
    }
}
