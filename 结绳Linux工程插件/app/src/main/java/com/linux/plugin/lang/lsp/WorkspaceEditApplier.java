package com.linux.plugin.lang.lsp;

import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.protocol.Uris;
import com.rapid.framework.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 把 LSP {@code WorkspaceEdit} 落到文件上。
 *
 * <p>支持两种形态：
 * <ul>
 *   <li>{@code changes: {uri: TextEdit[]}}（旧式，服务端多用这个）；</li>
 *   <li>{@code documentChanges: [...]}（含 TextDocumentEdit / CreateFile / RenameFile / DeleteFile）。</li>
 * </ul>
 *
 * <p>编辑顺序很关键：同一文件内必须按位置<b>从后往前</b>应用，
 * 否则前面的替换会把后面编辑的偏移量改掉。
 */
public final class WorkspaceEditApplier {

    /** 应用结果汇总。 */
    public static final class Result {

        public int changedFiles;
        public int appliedEdits;
        public int skippedEdits;
        public final List<String> failures = new ArrayList<>();

        public boolean isSuccess() {
            return failures.isEmpty();
        }

        @Override
        public String toString() {
            return "改动文件 " + changedFiles + " 个 / 应用编辑 " + appliedEdits
                    + " 处 / 跳过 " + skippedEdits + " 处"
                    + (failures.isEmpty() ? "" : " / 失败: " + failures);
        }
    }

    private final VirtualFileResolver resolver;

    public WorkspaceEditApplier(VirtualFileResolver resolver) {
        this.resolver = resolver;
    }

    /** 应用一个完整的 {@code workspaceEdit}。 */
    public Result apply(Map<String, Object> workspaceEdit) {
        Result result = new Result();
        if (workspaceEdit == null) {
            return result;
        }

        // changes 形态。
        Map<String, Object> changes = Json.object(workspaceEdit, "changes");
        if (changes != null) {
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                applyEdits(entry.getKey(), Json.array(entry.getValue()), result);
            }
        }

        // documentChanges 形态。
        for (Object entry : Json.array(workspaceEdit, "documentChanges")) {
            Map<String, Object> change = Json.object(entry);
            if (change == null) {
                continue;
            }
            if (change.get("textDocument") != null) {
                Map<String, Object> identifier = Json.object(change, "textDocument");
                applyEdits(Json.string(identifier, "uri", null), Json.array(change, "edits"), result);
            } else if (change.get("kind") != null) {
                applyResourceOperation(change, result);
            }
        }
        return result;
    }

    /**
     * 逐个应用同一文件上的编辑。
     *
     * @param uri 目标文件 URI
     */
    public void applyEdits(String uri, List<Object> entries, Result result) {
        if (uri == null || entries == null || entries.isEmpty()) {
            return;
        }
        VirtualFile file = resolver == null ? null : resolver.resolve(uri);
        if (file == null || !file.isFile()) {
            result.failures.add("找不到文件: " + uri);
            return;
        }

        List<TextEditEntry> edits = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            Map<String, Object> rangeValue = Json.object(object, "range");
            String newText = Json.string(object, "newText", null);
            if (rangeValue == null || newText == null) {
                result.skippedEdits++;
                continue;
            }
            edits.add(new TextEditEntry(
                    Json.integer(Json.object(rangeValue, "start"), "line", 0),
                    Json.integer(Json.object(rangeValue, "start"), "character", 0),
                    Json.integer(Json.object(rangeValue, "end"), "line", 0),
                    Json.integer(Json.object(rangeValue, "end"), "character", 0),
                    newText));
        }
        if (edits.isEmpty()) {
            return;
        }

        String content;
        try {
            content = file.asString();
        } catch (Throwable t) {
            result.failures.add("读取失败 " + file.getPath() + ": " + t.getMessage());
            return;
        }
        if (content == null) {
            result.failures.add("读取到空内容: " + file.getPath());
            return;
        }

        // 从后往前应用，保证前面的偏移量仍然有效。
        java.util.Collections.sort(edits, new Comparator<TextEditEntry>() {
            @Override
            public int compare(TextEditEntry left, TextEditEntry right) {
                if (left.startLine != right.startLine) {
                    return right.startLine - left.startLine;
                }
                return right.startCharacter - left.startCharacter;
            }
        });

        LineIndex index = new LineIndex(content);
        StringBuilder builder = new StringBuilder(content);
        int applied = 0;
        for (TextEditEntry edit : edits) {
            int start = index.offsetOf(edit.startLine, edit.startCharacter);
            int end = index.offsetOf(edit.endLine, edit.endCharacter);
            if (start < 0 || end < 0 || end < start || end > builder.length()) {
                result.skippedEdits++;
                continue;
            }
            builder.replace(start, end, edit.newText);
            applied++;
        }
        if (applied == 0) {
            return;
        }
        if (!write(file, builder.toString())) {
            result.failures.add("写入失败: " + file.getPath());
            return;
        }
        result.changedFiles++;
        result.appliedEdits += applied;
    }

    /** 处理 CreateFile / RenameFile / DeleteFile。 */
    private void applyResourceOperation(Map<String, Object> change, Result result) {
        String kind = Json.string(change, "kind", "");
        String uri = Json.string(change, "uri", null);
        if ("create".equals(kind)) {
            VirtualFile target = uri == null || resolver == null ? null : resolver.resolve(uri);
            if (target == null) {
                result.skippedEdits++;
                return;
            }
            if (!write(target, "")) {
                result.failures.add("创建失败: " + uri);
            } else {
                result.changedFiles++;
            }
            return;
        }
        if ("rename".equals(kind)) {
            String oldUri = Json.string(change, "oldUri", null);
            String newUri = Json.string(change, "newUri", null);
            java.io.File newFile = Uris.toFile(newUri);
            String newName = newFile == null ? null : newFile.getName();            VirtualFile source = oldUri == null || resolver == null ? null : resolver.resolve(oldUri);
            if (source == null || newName == null) {
                result.skippedEdits++;
                return;
            }
            try {
                source.rename(newName);
                result.changedFiles++;
            } catch (Throwable t) {
                result.failures.add("重命名失败 " + oldUri + ": " + t.getMessage());
            }
            return;
        }
        if ("delete".equals(kind)) {
            VirtualFile target = uri == null || resolver == null ? null : resolver.resolve(uri);
            if (target == null) {
                result.skippedEdits++;
                return;
            }
            try {
                target.delete();
                result.changedFiles++;
            } catch (Throwable t) {
                result.failures.add("删除失败 " + uri + ": " + t.getMessage());
            }
            return;
        }
        result.skippedEdits++;
    }

    private static boolean write(VirtualFile file, String content) {
        java.io.OutputStream out = null;
        try {
            out = file.openOutputStream();
            out.write(content.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** LSP 文本编辑：行列都是 0 基。 */
    private static final class TextEditEntry {

        final int startLine;
        final int startCharacter;
        final int endLine;
        final int endCharacter;
        final String newText;

        TextEditEntry(int startLine, int startCharacter, int endLine, int endCharacter, String newText) {
            this.startLine = startLine;
            this.startCharacter = startCharacter;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.newText = newText;
        }
    }

    /**
     * 行首偏移索引：把 (line, character) 换算成字符串下标。
     *
     * <p>前置构建一次行首表，避免每处编辑都从头扫文本。
     */
    static final class LineIndex {

        private final int[] lineStarts;
        private final int length;

        LineIndex(String content) {
            this.length = content.length();
            List<Integer> starts = new ArrayList<>();
            starts.add(Integer.valueOf(0));
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    starts.add(Integer.valueOf(i + 1));
                }
            }
            this.lineStarts = new int[starts.size()];
            for (int i = 0; i < starts.size(); i++) {
                lineStarts[i] = starts.get(i).intValue();
            }
        }

        /**
         * 换算下标；越界时尽量夹紧到合法范围（服务端偶尔会给出超出一行的列号）。
         *
         * @return 下标；行号明显非法时返回 -1
         */
        int offsetOf(int line, int character) {
            if (line < 0 || line >= lineStarts.length) {
                if (line < 0) {
                    return -1;
                }
                // 超出末尾：当作文件结尾。
                return length;
            }
            int start = lineStarts[line];
            int end = line + 1 < lineStarts.length ? lineStarts[line + 1] : length;
            int offset = start + Math.max(0, character);
            if (offset > end) {
                offset = end;
            }
            return Math.min(offset, length);
        }
    }
}
