package com.linux.plugin.lang.lsp;

import com.linux.plugin.lang.lsp.bridge.LspBridgeContext;
import com.linux.plugin.lang.lsp.bridge.LspCompletionProvider;
import com.linux.plugin.lang.lsp.bridge.LspEditProviders;
import com.linux.plugin.lang.lsp.bridge.LspHighlightProviders;
import com.linux.plugin.lang.lsp.bridge.LspHoverProvider;
import com.linux.plugin.lang.lsp.bridge.LspNavigationProviders;
import com.linux.plugin.lang.lsp.bridge.LspRefactorProviders;
import com.linux.plugin.lang.lsp.bridge.LspSimpleProviders;
import com.linux.plugin.lsp.LspLogger;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.DefaultLanguageEngine;
import com.rapid.api.lang.engine.LanguageContext;

/**
 * 把 LSP 服务端接成结绳的语言引擎。
 *
 * <p>这是桥接层与结绳框架的汇合点：构造函数里把所有 provider 装到
 * {@link DefaultLanguageEngine} 的各个插槽上，之后编辑器调用补全/跳转/悬停……
 * 都会走到 {@code com.linux.plugin.lang.lsp.bridge} 里对应的 LSP 转发。
 *
 * <p>不带 LSP 的服务端能力由 provider 自己判断：服务端没声明某能力时返回空结果，
 * 因此这里可以放心全量注册，不需要按能力动态裁剪。
 */
public class LspLanguageEngine extends DefaultLanguageEngine {

    private final DefaultLanguageContext languageContext;
    private final LspBridgeContext bridge;

    /**
     * @param languageContext 结绳语言上下文（同时用于取未保存正文）
     * @param bridge          已由 {@link LspEngines} 注入连接与工程目录的桥接上下文
     */
    public LspLanguageEngine(DefaultLanguageContext languageContext, LspBridgeContext bridge) {
        if (languageContext == null) {
            throw new IllegalArgumentException("languageContext 不能为空");
        }
        if (bridge == null) {
            throw new IllegalArgumentException("bridge 不能为空");
        }
        this.languageContext = languageContext;
        this.bridge = bridge;
        // 让桥接层知道"未保存正文"从哪来。
        bridge.setLanguageContext(languageContext);

        installProviders();
    }

    private void installProviders() {
        setCompletion(new LspCompletionProvider(languageContext, bridge));
        setHover(new LspHoverProvider(languageContext, bridge));
        setSignatureHelp(new LspSimpleProviders.Signature(languageContext, bridge));

        setDefinition(new LspNavigationProviders.Definition(languageContext, bridge));
        setImplementation(new LspNavigationProviders.Implementation(languageContext, bridge));
        setReference(new LspNavigationProviders.References(languageContext, bridge));

        setSymbol(new LspSimpleProviders.Symbols(languageContext, bridge));
        setFolding(new LspSimpleProviders.Folding(languageContext, bridge));
        setHighlight(new LspHighlightProviders.HighlightAtPosition(languageContext, bridge));
        setCodeLens(new LspHighlightProviders.CodeLenses(languageContext, bridge));

        setLint(new LspSimpleProviders.Diagnostics(languageContext, bridge));

        setRename(new LspEditProviders.Rename(languageContext, bridge));
        setFormatting(new LspEditProviders.Formatting(languageContext, bridge));
        setCodeAction(new LspEditProviders.CodeActions(languageContext, bridge));
        setRefactor(new LspRefactorProviders.Refactor(languageContext, bridge));
        setSourceGenerating(new LspRefactorProviders.SourceGenerating(languageContext, bridge));

        // 刻意不注册 ElementProvider：结绳用它做"元素结构视图"，
        // LSP 没有等价且可靠的对应能力，硬接会给出半成品结果。
    }

    @Override
    public LanguageContext getContext() {
        return languageContext;
    }

    /** 暴露桥接上下文，方便上层在工程切换时更新 workspace 与连接。 */
    public LspBridgeContext getBridge() {
        return bridge;
    }

    /** 供日志使用。 */
    public LspLogger getLogger() {
        return bridge.getLogger();
    }
}
