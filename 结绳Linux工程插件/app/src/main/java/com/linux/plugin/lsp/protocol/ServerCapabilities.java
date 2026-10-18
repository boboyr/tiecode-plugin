package com.linux.plugin.lsp.protocol;

import com.linux.plugin.lsp.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 服务端在 {@code initialize} 响应里声明的能力。
 *
 * <p>桥接层用它决定"哪些 provider 该注册、哪些该让位给结绳自带能力"，
 * 以及补全触发字符等细节。
 *
 * <p>LSP 的能力字段有个坑：既可能是 {@code true}，也可能是配置对象。
 * {@link #providerObject} 把两种情况统一成"有就是支持、对象里带细节"。
 */
public final class ServerCapabilities {

    private static final String KEY_TEXT_DOCUMENT_SYNC = "textDocumentSync";

    private final Map<String, Object> raw;

    private final boolean completion;
    private final boolean hover;
    private final boolean definition;
    private final boolean declaration;
    private final boolean typeDefinition;
    private final boolean implementation;
    private final boolean references;
    private final boolean documentHighlight;
    private final boolean documentSymbol;
    private final boolean workspaceSymbol;
    private final boolean foldingRange;
    private final boolean codeAction;
    private final boolean formatting;
    private final boolean rangeFormatting;
    private final boolean rename;
    private final boolean renamePrepare;
    private final boolean codeLens;
    private final boolean signatureHelp;
    private final boolean completionResolve;
    private final boolean hierarchicalDocumentSymbol;

    /** {@code textDocumentSync.change}：0=不关心、1=全量、2=增量。 */
    private final int syncChangeKind;

    /** {@code textDocumentSync.openClose}：是否关心打开/关闭事件。 */
    private final boolean openCloseSync;

    private final List<String> completionTriggerCharacters;
    private final List<String> signatureTriggerCharacters;
    private final List<String> codeActionKinds;

    public ServerCapabilities(Map<String, Object> capabilities) {
        this.raw = capabilities == null ? Collections.<String, Object>emptyMap() : capabilities;

        Map<String, Object> completionCap = providerObject(raw, "completionProvider");
        Map<String, Object> hoverCap = providerObject(raw, "hoverProvider");
        Map<String, Object> definitionCap = providerObject(raw, "definitionProvider");
        Map<String, Object> declarationCap = providerObject(raw, "declarationProvider");
        Map<String, Object> typeDefinitionCap = providerObject(raw, "typeDefinitionProvider");
        Map<String, Object> implementationCap = providerObject(raw, "implementationProvider");
        Map<String, Object> referencesCap = providerObject(raw, "referencesProvider");
        Map<String, Object> highlightCap = providerObject(raw, "documentHighlightProvider");
        Map<String, Object> documentSymbolCap = providerObject(raw, "documentSymbolProvider");
        Map<String, Object> workspaceSymbolCap = providerObject(raw, "workspaceSymbolProvider");
        Map<String, Object> foldingCap = providerObject(raw, "foldingRangeProvider");
        Map<String, Object> codeActionCap = providerObject(raw, "codeActionProvider");
        Map<String, Object> formattingCap = providerObject(raw, "documentFormattingProvider");
        Map<String, Object> rangeFormattingCap = providerObject(raw, "documentRangeFormattingProvider");
        Map<String, Object> renameCap = providerObject(raw, "renameProvider");
        Map<String, Object> codeLensCap = providerObject(raw, "codeLensProvider");
        Map<String, Object> signatureCap = providerObject(raw, "signatureHelpProvider");

        this.completion = completionCap != null;
        this.hover = hoverCap != null;
        this.definition = definitionCap != null;
        this.declaration = declarationCap != null;
        this.typeDefinition = typeDefinitionCap != null;
        this.implementation = implementationCap != null;
        this.references = referencesCap != null;
        this.documentHighlight = highlightCap != null;
        this.documentSymbol = documentSymbolCap != null;
        this.workspaceSymbol = workspaceSymbolCap != null;
        this.foldingRange = foldingCap != null;
        this.codeAction = codeActionCap != null;
        this.formatting = formattingCap != null;
        this.rangeFormatting = rangeFormattingCap != null;
        this.rename = renameCap != null;
        this.renamePrepare = renameCap != null && Json.bool(renameCap, "prepareProvider", false);
        this.codeLens = codeLensCap != null;
        this.signatureHelp = signatureCap != null;
        this.completionResolve = Json.bool(completionCap, "resolveProvider", false);

        this.completionTriggerCharacters = stringList(completionCap, "triggerCharacters");
        this.signatureTriggerCharacters = stringList(signatureCap, "triggerCharacters");
        this.codeActionKinds = stringList(codeActionCap, "codeActionKinds");

        this.syncChangeKind = readSyncChangeKind(raw);
        this.openCloseSync = readOpenClose(raw);
        this.hierarchicalDocumentSymbol = Json.bool(raw, "hierarchicalDocumentSymbolSupport", false);
    }

    /**
     * 读取 {@code textDocumentSync}。
     *
     * <p>两种写法：老的裸数字（{@code 1}/{@code 2}），或对象
     * {@code {"openClose": true, "change": 2}}。
     */
    private static int readSyncChangeKind(Map<String, Object> capabilities) {
        Object sync = capabilities.get(KEY_TEXT_DOCUMENT_SYNC);
        if (sync instanceof Number) {
            return ((Number) sync).intValue();
        }
        Map<String, Object> object = Json.object(sync);
        if (object == null) {
            return 0;
        }
        return Json.integer(object, "change", 0);
    }

    private static boolean readOpenClose(Map<String, Object> capabilities) {
        Object sync = capabilities.get(KEY_TEXT_DOCUMENT_SYNC);
        if (sync instanceof Number) {
            return ((Number) sync).intValue() != 0;
        }
        Map<String, Object> object = Json.object(sync);
        return object != null && Json.bool(object, "openClose", false);
    }

    /** {@code textDocumentSync.change}：0=不关心、1=全量、2=增量。 */
    public int getSyncChangeKind() {
        return syncChangeKind;
    }

    /** 服务端没有声明增量同步能力时，按全量发送变更。 */
    public boolean prefersFullSync() {
        return syncChangeKind != 2;
    }

    public boolean isOpenCloseSync() {
        return openCloseSync;
    }

    public boolean supportsCompletion() {
        return completion;
    }

    public boolean supportsHover() {
        return hover;
    }

    public boolean supportsDefinition() {
        return definition;
    }

    public boolean supportsDeclaration() {
        return declaration;
    }

    public boolean supportsTypeDefinition() {
        return typeDefinition;
    }

    public boolean supportsImplementation() {
        return implementation;
    }

    public boolean supportsReferences() {
        return references;
    }

    public boolean supportsDocumentHighlight() {
        return documentHighlight;
    }

    public boolean supportsDocumentSymbol() {
        return documentSymbol;
    }

    public boolean supportsWorkspaceSymbol() {
        return workspaceSymbol;
    }

    public boolean supportsFoldingRange() {
        return foldingRange;
    }

    public boolean supportsCodeAction() {
        return codeAction;
    }

    public boolean supportsFormatting() {
        return formatting;
    }

    public boolean supportsRangeFormatting() {
        return rangeFormatting;
    }

    public boolean supportsRename() {
        return rename;
    }

    /** 服务端是否支持 {@code textDocument/prepareRename}。 */
    public boolean supportsRenamePrepare() {
        return renamePrepare;
    }

    public boolean supportsCodeLens() {
        return codeLens;
    }

    public boolean supportsSignatureHelp() {
        return signatureHelp;
    }

    /** 是否需要 {@code completionItem/resolve} 才能拿到文档说明。 */
    public boolean supportsCompletionResolve() {
        return completionResolve;
    }

    public boolean supportsHierarchicalDocumentSymbol() {
        return hierarchicalDocumentSymbol;
    }

    public List<String> getCompletionTriggerCharacters() {
        return completionTriggerCharacters;
    }

    public List<String> getSignatureTriggerCharacters() {
        return signatureTriggerCharacters;
    }

    public List<String> getCodeActionKinds() {
        return codeActionKinds;
    }

    /** 原始 capabilities，便于上层取未解析字段。 */
    public Map<String, Object> getRaw() {
        return raw;
    }

    @Override
    public String toString() {
        return "ServerCapabilities{completion=" + completion + ", hover=" + hover
                + ", definition=" + definition + ", references=" + references
                + ", documentSymbol=" + documentSymbol + ", rename=" + rename
                + ", codeAction=" + codeAction + ", formatting=" + formatting
                + ", folding=" + foldingRange + ", syncChange=" + syncChangeKind + "}";
    }

    /**
     * 读取"布尔或对象"形式的能力字段。
     *
     * @return 配置对象；字段为 {@code true} 时返回空对象；缺失或 {@code false} 返回 null
     */
    private static Map<String, Object> providerObject(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value == null || Boolean.FALSE.equals(value)) {
            return null;
        }
        if (Boolean.TRUE.equals(value)) {
            return Json.obj();
        }
        Map<String, Object> object = Json.object(value);
        return object == null ? Json.obj() : object;
    }

    private static List<String> stringList(Map<String, Object> parent, String key) {
        if (parent == null) {
            return Collections.emptyList();
        }
        List<Object> values = Json.array(parent, key);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            String text = Json.string(value, null);
            if (text != null) {
                result.add(text);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
