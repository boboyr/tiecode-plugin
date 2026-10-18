package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspConverters;
import com.linux.plugin.lsp.json.Json;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.baseModel.DocumentPosition;
import com.rapid.api.lang.engine.hover.Hover;
import com.rapid.api.lang.engine.hover.HoverProvider;
import com.rapid.api.lang.engine.hover.MarkedString;
import com.rapid.framework.data.document.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code textDocument/hover} 的桥接。 */
public final class LspHoverProvider extends LspBaseProvider implements HoverProvider {

    public LspHoverProvider(DefaultLanguageContext context, LspBridgeContext bridge) {
        super(context, bridge);
    }

    @Override
    public Hover hover(DocumentPosition documentPosition, com.rapid.api.lang.engine.CancellationToken token) {
        logger().info("HoverProvider 被调用");
        String uri = openUri(documentPosition == null ? null : documentPosition.virtualFile);
        if (uri == null) {
            return null;
        }
        Object response;
        try {
            response = client().requestIfSupported(
                    client().getCapabilities().supportsHover(),
                    "textDocument/hover",
                    textDocumentPosition(uri, documentPosition.position));
        } catch (Throwable t) {
            logger().warning("悬停请求失败: " + t.getMessage());
            return null;
        }

        Map<String, Object> object = Json.object(response);
        if (object == null) {
            return null;
        }
        Object contents = object.get("contents");
        if (contents == null) {
            return null;
        }

        Hover hover = new Hover();
        hover.contents = markedStrings(contents);
        Map<String, Object> rangeValue = Json.object(object, "range");
        hover.range = rangeValue == null ? Range.NONE : LspConverters.range(rangeValue);
        return hover;
    }

    /**
     * LSP 的 hover contents 允许三种形态：MarkupContent、MarkedString、或二者数组。
     *
     * <p>{@code MarkedString} 的另一种写法是 {@code {language, value}} 对象，
     * 这里都归一化成结绳的 {@link MarkedString}。
     */
    private List<MarkedString> markedStrings(Object contents) {
        List<MarkedString> result = new ArrayList<>();
        if (contents instanceof List) {
            for (Object entry : Json.array(contents)) {
                append(result, entry);
            }
        } else {
            append(result, contents);
        }
        return result;
    }

    private void append(List<MarkedString> result, Object entry) {
        if (entry == null) {
            return;
        }
        if (entry instanceof String) {
            MarkedString marked = new MarkedString();
            marked.language = "markdown";
            marked.value = (String) entry;
            result.add(marked);
            return;
        }
        Map<String, Object> object = Json.object(entry);
        if (object == null) {
            return;
        }
        MarkedString marked = new MarkedString();
        // MarkupContent 用 kind，MarkedString 用 language；两者都可能缺省。
        String language = Json.string(object, "language", null);
        if (language == null) {
            language = Json.string(object, "kind", "markdown");
        }
        marked.language = language;
        marked.value = Json.string(object, "value", "");
        result.add(marked);
    }
}
