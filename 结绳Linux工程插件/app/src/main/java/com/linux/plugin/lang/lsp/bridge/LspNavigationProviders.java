package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspConverters;
import com.linux.plugin.lsp.json.Json;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.baseModel.DocumentPosition;
import com.rapid.api.lang.engine.definition.DefinitionProvider;
import com.rapid.api.lang.engine.implementation.ImplementationProvider;
import com.rapid.api.lang.engine.reference.ReferenceProvider;
import com.rapid.framework.data.document.Location;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 三类"跳到某处"的桥接：定义、实现、引用。
 *
 * <p>三者请求与响应形态完全一致（{@code Location | Location[] | LocationLink[]}），
 * 只是方法名不同，因此放一处实现，减少三份重复代码。
 */
public final class LspNavigationProviders {

    private LspNavigationProviders() {
    }

    /** {@code textDocument/definition}。 */
    public static final class Definition extends LspBaseProvider implements DefinitionProvider {

        public Definition(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<Location> gotoDefinitions(DocumentPosition documentPosition,
                                             com.rapid.api.lang.engine.CancellationToken token) {
            logger().info("DefinitionProvider 被调用");
            String uri = openUri(documentPosition == null ? null : documentPosition.virtualFile);
            if (uri == null) {
                return Collections.emptyList();
            }
            try {
                Object response = client().requestIfSupported(
                        client().getCapabilities().supportsDefinition(),
                        "textDocument/definition",
                        textDocumentPosition(uri, documentPosition.position));
                return LspConverters.locations(response, resolver());
            } catch (Throwable t) {
                logger().warning("定义跳转失败: " + t.getMessage());
                return Collections.emptyList();
            }
        }
    }

    /** {@code textDocument/implementation}。 */
    public static final class Implementation extends LspBaseProvider implements ImplementationProvider {

        public Implementation(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<Location> gotoImplementation(DocumentPosition documentPosition,
                                                com.rapid.api.lang.engine.CancellationToken token) {
            String uri = openUri(documentPosition == null ? null : documentPosition.virtualFile);
            if (uri == null) {
                return Collections.emptyList();
            }
            try {
                Object response = client().requestIfSupported(
                        client().getCapabilities().supportsImplementation(),
                        "textDocument/implementation",
                        textDocumentPosition(uri, documentPosition.position));
                return LspConverters.locations(response, resolver());
            } catch (Throwable t) {
                logger().warning("实现跳转失败: " + t.getMessage());
                return Collections.emptyList();
            }
        }
    }

    /** {@code textDocument/references}。 */
    public static final class References extends LspBaseProvider implements ReferenceProvider {

        public References(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public List<Location> findReferences(DocumentPosition documentPosition,
                                             com.rapid.api.lang.engine.CancellationToken token) {
            String uri = openUri(documentPosition == null ? null : documentPosition.virtualFile);
            if (uri == null) {
                return Collections.emptyList();
            }
            Map<String, Object> request = textDocumentPosition(uri, documentPosition.position);
            // 查找引用时通常希望把声明也算进去，与 IDE 的"查找用法"一致。
            request.put("context", Json.obj("includeDeclaration", Boolean.TRUE));
            try {
                Object response = client().requestIfSupported(
                        client().getCapabilities().supportsReferences(),
                        "textDocument/references",
                        request);
                return LspConverters.locations(response, resolver());
            } catch (Throwable t) {
                logger().warning("查找引用失败: " + t.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
