package com.linux.plugin.lang.lsp.bridge;

import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.action.CodeAction;
import com.rapid.api.lang.engine.baseModel.DocumentRange;
import com.rapid.api.lang.engine.refactor.RefactorProvider;
import com.rapid.api.lang.engine.source.SourceGeneratingProvider;

import java.util.Collections;
import java.util.List;

/**
 * {@code refactor} 与 {@code sourceGenerating} 两个"无诊断上下文"的代码操作入口。
 *
 * <p>它们与 {@code action.CodeActionProvider} 的差别只有
 * "要不要带诊断上下文"和"要不要按 kind 过滤"，
 * 请求与落地逻辑复用 {@code LspCodeActionsSupport}，不重复实现。
 *
 * <p>为什么单独拆出：结绳会在这两个入口上挂不同类型的菜单项
 * （重构 vs 源码生成），服务端靠 {@code context.only} 区分。
 */
public final class LspRefactorProviders {

    /** 重构入口只取 refactor.* 类别的操作。 */
    private static final List<String> REFACTOR_KINDS = Collections.unmodifiableList(
            java.util.Arrays.asList("refactor", "refactor.extract", "refactor.inline", "refactor.rewrite"));

    /** 源码生成入口只取 source.* 类别的操作。 */
    private static final List<String> SOURCE_KINDS = Collections.unmodifiableList(
            java.util.Arrays.asList("source", "source.organizeImports", "source.fixAll"));

    private LspRefactorProviders() {
    }

    /** {@code textDocument/codeAction}（重构入口）。 */
    public static final class Refactor extends LspBaseProvider implements RefactorProvider {

        private final LspCodeActionsSupport support;

        public Refactor(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
            this.support = new LspCodeActionsSupport(bridge);
        }

        @Override
        public List<CodeAction> codeActions(DocumentRange documentRange,
                                            com.rapid.api.lang.engine.CancellationToken token) {
            return support.request(documentRange, null, REFACTOR_KINDS);
        }
    }

    /** {@code textDocument/codeAction}（源码生成入口）。 */
    public static final class SourceGenerating extends LspBaseProvider implements SourceGeneratingProvider {

        private final LspCodeActionsSupport support;

        public SourceGenerating(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
            this.support = new LspCodeActionsSupport(bridge);
        }

        @Override
        public List<CodeAction> codeActions(DocumentRange documentRange,
                                            com.rapid.api.lang.engine.CancellationToken token) {
            return support.request(documentRange, null, SOURCE_KINDS);
        }
    }
}
