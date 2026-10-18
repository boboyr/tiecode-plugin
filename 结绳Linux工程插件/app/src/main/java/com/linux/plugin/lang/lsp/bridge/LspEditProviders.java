package com.linux.plugin.lang.lsp.bridge;

import com.linux.plugin.lang.lsp.LspConverters;
import com.linux.plugin.lang.lsp.VirtualFileResolver;
import com.linux.plugin.lsp.json.Json;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.action.CodeAction;
import com.rapid.api.lang.engine.action.CodeActionContext;
import com.rapid.api.lang.engine.action.CodeActionProvider;
import com.rapid.api.lang.engine.baseModel.DocumentPosition;
import com.rapid.api.lang.engine.baseModel.DocumentRange;
import com.rapid.api.lang.engine.baseModel.TextEdit;
import com.rapid.api.lang.engine.baseModel.WorkspaceEdit;
import com.rapid.api.lang.engine.formatting.FormattingProvider;
import com.rapid.api.lang.engine.rename.RenameDetail;
import com.rapid.api.lang.engine.rename.RenameProvider;
import com.rapid.framework.data.document.Range;
import com.rapid.framework.vfs.VirtualFile;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * "会改动文件"的语言服务桥接：重命名、代码操作、格式化。
 *
 * <p>三者的共同点是服务端返回 {@code WorkspaceEdit} / {@code TextEdit[]}，
 * 需要真正落到文件上，因此统一走 {@code WorkspaceEditApplier}。
 */
public final class LspEditProviders {

    private LspEditProviders() {
    }

    /** {@code textDocument/prepareRename} + {@code textDocument/rename}。 */
    public static final class Rename extends LspBaseProvider implements RenameProvider {

        public Rename(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public RenameDetail prepareRename(DocumentPosition documentPosition,
                                          com.rapid.api.lang.engine.CancellationToken token) {
            String uri = openUri(documentPosition == null ? null : documentPosition.virtualFile);
            if (uri == null) {
                return null;
            }
            Object response;
            try {
                response = client().requestIfSupported(
                        client().getCapabilities().supportsRenamePrepare(),
                        "textDocument/prepareRename",
                        textDocumentPosition(uri, documentPosition.position));
            } catch (Throwable t) {
                logger().warning("准备重命名失败: " + t.getMessage());
                return null;
            }

            RenameDetail detail = new RenameDetail();
            if (response == null) {
                // 服务端不支持 prepareRename：退化为"以光标处为占位符"，
                // 让编辑器继续走 rename 流程。
                detail.range = new Range(documentPosition.position, documentPosition.position);
                detail.placeholder = "";
                return detail;
            }
            Map<String, Object> object = Json.object(response);
            if (object != null && object.get("start") != null) {
                // 直接返回 Range 的形态。
                detail.range = LspConverters.range(object);
                detail.placeholder = "";
                return detail;
            }
            if (object != null) {
                // 返回 {range, placeholder} 的形态。
                Map<String, Object> rangeValue = Json.object(object, "range");
                detail.range = rangeValue == null
                        ? new Range(documentPosition.position, documentPosition.position)
                        : LspConverters.range(rangeValue);
                detail.placeholder = Json.string(object, "placeholder", "");
                return detail;
            }
            // prepareRename 也可能返回一个字符串（旧式实现）。
            detail.range = new Range(documentPosition.position, documentPosition.position);
            detail.placeholder = Json.string(response, "");
            return detail;
        }

        @Override
        public boolean isValidName(String name) {
            // 结绳会先调这个再调 rename；这里只做最基本的非空校验，
            // 真正的合法性由服务端在 rename 时判定（它会返回错误）。
            return ready() && client().getCapabilities().supportsRename()
                    && name != null && !name.trim().isEmpty();
        }

        @Override
        public WorkspaceEdit rename(DocumentRange documentRange, String newName,
                                    com.rapid.api.lang.engine.CancellationToken token) {
            if (documentRange == null || newName == null) {
                return null;
            }
            String uri = openUri(documentRange.virtualFile);
            if (uri == null) {
                return null;
            }
            Map<String, Object> request = Json.obj(
                    "textDocument", Json.obj("uri", uri),
                    "position", lspPosition(documentRange.range == null ? null : documentRange.range.start),
                    "newName", newName);

            Object response;
            try {
                response = client().requestIfSupported(
                        client().getCapabilities().supportsRename(),
                        "textDocument/rename",
                        request);
            } catch (Throwable t) {
                logger().warning("重命名失败: " + t.getMessage());
                return null;
            }
            return toWorkspaceEdit(response, resolver());
        }
    }

    /** {@code textDocument/codeAction}（带诊断上下文的入口）。 */
    public static final class CodeActions extends LspBaseProvider implements CodeActionProvider {

        private final LspCodeActionsSupport support;

        public CodeActions(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
            this.support = new LspCodeActionsSupport(bridge);
        }

        @Override
        public List<CodeAction> codeActions(DocumentRange documentRange,
                                            CodeActionContext context,
                                            com.rapid.api.lang.engine.CancellationToken token) {
            List<com.rapid.api.lang.engine.lint.Diagnostic> diagnostics =
                    context == null ? null : context.diagnostics;
            // 不带 kind 过滤：带诊断的入口应该拿到服务端提供的全部修复项。
            return support.request(documentRange, diagnostics, null);
        }
    }

    /** {@code textDocument/formatting}。 */
    public static final class Formatting extends LspBaseProvider implements FormattingProvider {

        /** 结绳侧没有把缩进配置传进来，先按 4 空格——与结绳默认风格一致。 */
        private static final int DEFAULT_TAB_SIZE = 4;

        public Formatting(DefaultLanguageContext context, LspBridgeContext bridge) {
            super(context, bridge);
        }

        @Override
        public boolean shouldFormat(String text) {
            return ready() && client().getCapabilities().supportsFormatting();
        }

        @Override
        public List<TextEdit> formatting(DocumentRange documentRange,
                                        com.rapid.api.lang.engine.CancellationToken token) {
            if (documentRange == null) {
                return Collections.emptyList();
            }
            String uri = openUri(documentRange.virtualFile);
            if (uri == null) {
                return Collections.emptyList();
            }
            Map<String, Object> options = Json.obj(
                    "tabSize", Integer.valueOf(DEFAULT_TAB_SIZE),
                    "insertSpaces", Boolean.TRUE);

            Object response;
            try {
                response = client().requestIfSupported(
                        client().getCapabilities().supportsFormatting(),
                        "textDocument/formatting",
                        Json.obj("textDocument", Json.obj("uri", uri), "options", options));
            } catch (Throwable t) {
                logger().warning("格式化请求失败: " + t.getMessage());
                return Collections.emptyList();
            }
            return LspConverters.textEdits(response);
        }
    }

    /**
     * LSP {@code workspaceEdit} → 结绳 {@link WorkspaceEdit}。
     *
     * <p>结绳模型要求每个编辑挂到一个 {@link VirtualFile} 上，
     * 因此解析不到文件的条目会被丢弃（而不是留一个悬空编辑）。
     */
    private static WorkspaceEdit toWorkspaceEdit(Object response, VirtualFileResolver resolver) {
        Map<String, Object> edit = Json.object(response);
        if (edit == null) {
            return null;
        }
        WorkspaceEdit result = new WorkspaceEdit();
        boolean any = false;

        Map<String, Object> changes = Json.object(edit, "changes");
        if (changes != null) {
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                VirtualFile file = resolver == null ? null : resolver.resolve(entry.getKey());
                if (file == null) {
                    continue;
                }
                List<TextEdit> edits = LspConverters.textEdits(entry.getValue());
                if (!edits.isEmpty()) {
                    result.addChange(file, edits);
                    any = true;
                }
            }
        }

        for (Object entry : Json.array(edit, "documentChanges")) {
            Map<String, Object> change = Json.object(entry);
            if (change == null || change.get("textDocument") == null) {
                // CreateFile / RenameFile / DeleteFile 在结绳的 WorkspaceEdit 里没有对应表达，
                // 统一由 rename 后的返回值走文件系统落地。
                continue;
            }
            Map<String, Object> identifier = Json.object(change, "textDocument");
            VirtualFile file = resolver == null ? null : resolver.resolve(Json.string(identifier, "uri", null));
            if (file == null) {
                continue;
            }
            List<TextEdit> edits = LspConverters.textEdits(change.get("edits"));
            if (!edits.isEmpty()) {
                result.addChange(file, edits);
                any = true;
            }
        }
        return any ? result : null;
    }

}
