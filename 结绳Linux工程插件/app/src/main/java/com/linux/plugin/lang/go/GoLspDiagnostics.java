package com.linux.plugin.lang.go;

import com.linux.plugin.lang.lsp.LspDiagnostics;
import com.linux.plugin.lang.lsp.LspEngines;
import com.linux.plugin.lang.lsp.bridge.LspBridgeContext;
import com.linux.plugin.lsp.LspLogger;
import com.rapid.api.lang.engine.lint.Diagnostic;

import java.util.List;

/**
 * gopls 诊断的接收端。
 *
 * <p>这里要解决一个方向相反的接口问题：<b>LSP 是服务端推诊断，
 * 结绳的 {@code LintProvider} 是编辑器来拉</b>。{@link LspDiagnostics}
 * 已经做了推送缓存，本类负责把"有新诊断了"这件事通知出去。
 *
 * <p>默认只做一次去重日志（gopls 在编辑期间会频繁重推，逐条打日志会淹掉输出）。
 * 如果结绳提供了刷新问题面板的入口，用
 * {@link #setRefreshListener(Runnable)} 接上即可真正实时显示。
 */
public final class GoLspDiagnostics {

    private static volatile Runnable refreshListener;
    private static volatile String lastSummary;
    private static volatile int publishedCount;

    private GoLspDiagnostics() {
    }

    /**
     * 设置"诊断已更新"的刷新回调。
     *
     * @param listener 由结绳侧提供；传 null 则恢复为只打日志
     */
    public static void setRefreshListener(Runnable listener) {
        refreshListener = listener;
    }

    /** 当前诊断缓存；没有装配 gopls 时返回 null。 */
    public static LspDiagnostics store() {
        LspBridgeContext bridge = LspEngines.getBridge(GoLspContext.LANGUAGE_ID);
        return bridge == null ? null : bridge.getDiagnostics();
    }

    /** 读取某文件的诊断；没有则返回空列表。 */
    public static List<Diagnostic> of(String uri) {
        LspDiagnostics diagnostics = store();
        return diagnostics == null ? java.util.Collections.<Diagnostic>emptyList() : diagnostics.get(uri);
    }

    /** 是否有任意文件被推送过诊断。 */
    public static boolean hasAny() {
        LspDiagnostics diagnostics = store();
        return diagnostics != null && !diagnostics.uris().isEmpty();
    }

    /** 已处理过多少次推送，用于确认"通道确实在工作"。 */
    public static int publishedCount() {
        return publishedCount;
    }

    /** 清空缓存（工程切换时用）。 */
    public static void clear() {
        LspDiagnostics diagnostics = store();
        if (diagnostics != null) {
            diagnostics.clear();
        }
        lastSummary = null;
    }

    /**
     * 挂到 {@link LspDiagnostics} 上，接收推送通知。
     *
     * <p>该方法幂等，重复调用只是覆盖回调。
     */
    public static void attach() {
        final LspDiagnostics diagnostics = store();
        if (diagnostics == null) {
            return;
        }
        diagnostics.setChangeListener(new Runnable() {
            @Override
            public void run() {
                publishedCount++;
                String summary = summarize(diagnostics);
                // 只在"有/无诊断"或数量变化时打日志，避免打字期间刷屏。
                if (!summary.equals(lastSummary)) {
                    lastSummary = summary;
                    LspLogger.DEFAULT.info("gopls 诊断: " + summary);
                }
                Runnable listener = refreshListener;
                if (listener != null) {
                    try {
                        listener.run();
                    } catch (Throwable t) {
                        LspLogger.DEFAULT.warning("刷新问题面板失败: " + t.getMessage());
                    }
                }
            }
        });
    }

    /** 生成"文件数 / 条目数"的简短摘要。 */
    private static String summarize(LspDiagnostics diagnostics) {
        List<String> uris = diagnostics.uris();
        if (uris.isEmpty()) {
            return "暂无问题";
        }
        int total = 0;
        for (String uri : uris) {
            total += diagnostics.get(uri).size();
        }
        return uris.size() + " 个文件 / " + total + " 条";
    }
}
