package com.scala.android.project;

import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.action.CodeAction;
import com.rapid.api.lang.engine.action.CodeActionContext;
import com.rapid.api.lang.engine.action.CodeActionProvider;
import com.rapid.api.lang.engine.baseModel.DocumentRange;
import com.rapid.api.lang.engine.baseModel.TextEdit;
import com.rapid.api.lang.engine.baseModel.WorkspaceEdit;
import com.rapid.api.lang.engine.lint.Diagnostic;
import com.rapid.api.lang.engine.source.SourceGeneratingProvider;
import com.rapid.framework.data.document.Position;
import com.rapid.framework.data.document.Range;
import com.rapid.framework.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scala 快速修复（宿主里对应「生成代码」/「重构」菜单弹出的那个列表）。
 *
 * <p>两条：
 * <ol>
 *   <li><b>实现所有抽象方法</b>：光标停在
 *       {@code class X extends Base} 的类头行时，一次插入全部
 *       {@code override def … = ???} 骨架（骨架直接取编译器的诊断消息，
 *       和编译器自己建议的写法完全一致）；</li>
 *   <li><b>导入 xxx</b>：光标落在"找不到的名字/类型"上时，去 classpath 索引里查全限定名，
 *       在 import 区插入 {@code import com.foo.X}。</li>
 * </ol>
 *
 * <p>两个动作都算 workspaceEdit 交给宿主执行，所以是"点一下自动改代码"，
 * 用户不需要自己复制粘贴。
 */
public class ScalaCodeActionProvider implements CodeActionProvider, SourceGeneratingProvider {

    private static final Pattern NOT_FOUND = Pattern.compile("Not found: (?:type |value |object )?([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern CLASS_HEADER = Pattern.compile(
            "^\\s*(?:(?:case|abstract|sealed|final|open)\\s+)*(?:class|trait|object|enum)\\s+"
                    + "[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*\\[[^\\]]*\\])?"
                    + ".*\\bextends\\b.*$");

    /**
     * 宿主「生成代码」菜单的入口（{@code SourceGeneratingProvider}）。
     *
     * <p>宿主本体的「快速修复」通道（{@code CodeActionProvider}）在当前版本没有挂到任何菜单上，
     * 实测能点到的入口是编辑器菜单里的「生成代码」，所以两个接口都实现、共用一套逻辑。
     * 这条通道拿不到光标处的诊断，import 修复改为"按光标下的名字查 classpath 索引"。
     */
    @Override
    public List<CodeAction> codeActions(DocumentRange documentRange, CancellationToken token) {
        return collect(documentRange, null, token);
    }

    @Override
    public List<CodeAction> codeActions(DocumentRange documentRange, CodeActionContext context,
                                        CancellationToken token) {
        return collect(documentRange, context, token);
    }

    private List<CodeAction> collect(DocumentRange documentRange, CodeActionContext context,
                                     CancellationToken token) {
        if (documentRange == null || documentRange.virtualFile == null
                || token != null && token.isCancellationRequested) {
            return Collections.emptyList();
        }
        VirtualFile file = documentRange.virtualFile;
        String content;
        try {
            content = file.asString();
        } catch (Throwable error) {
            return Collections.emptyList();
        }
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        List<CodeAction> actions = new ArrayList<>();
        try {
            addImplementAbstractMembers(actions, file, content, documentRange, context);
            addImportFixes(actions, file, content, context);
            if (actions.isEmpty()) {
                addImportFixAtCursor(actions, file, content, documentRange);
            }
        } catch (Throwable ignored) {
            // 快速修复算不出来就当没有，不能影响编辑器。
        }
        return actions;
    }

    // ---------------------------------------------------- 实现所有抽象方法

    private void addImplementAbstractMembers(List<CodeAction> actions, VirtualFile file,
                                             String content, DocumentRange documentRange,
                                             CodeActionContext context) {
        int line = lineOf(documentRange);
        if (line < 0) {
            return;
        }
        String lineText = lineText(content, line);
        if (!ClassHeader.matches(lineText)) {
            return;
        }
        // 完整编译一次（内部有按内容 hash 的缓存），从编译器消息里取现成骨架
        List<String> stubs = ScalaAbstractMembers.stubs(content, ScalaAbstractMembers.classpath());
        if (stubs.isEmpty()) {
            return;
        }
        String indent = indentation(lineText) + "  ";
        StringBuilder insert = new StringBuilder();
        for (String stub : stubs) {
            insert.append('\n').append(indent).append(stub.trim());
        }
        insert.append('\n');
        Position at = new Position(line, lineText.length(), offsetOfLine(content, line) + lineText.length());
        CodeAction action = new CodeAction();
        action.title = "实现所有抽象方法（" + stubs.size() + " 个）";
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.addChange(file, new TextEdit(new Range(at, at), insert.toString()));
        action.workspaceEdit = edit;
        actions.add(action);
    }

    // ------------------------------------------------------------- 添加 import

    private void addImportFixes(List<CodeAction> actions, VirtualFile file, String content,
                                CodeActionContext context) {
        if (context == null || context.diagnostics == null) {
            return;
        }
        for (Diagnostic diagnostic : context.diagnostics) {
            if (diagnostic == null || diagnostic.message == null) {
                continue;
            }
            Matcher matcher = NOT_FOUND.matcher(diagnostic.message);
            while (matcher.find()) {
                String simpleName = matcher.group(1);
                String qualified = ScalaTypeIndex.find(simpleName);
                if (qualified == null || content.contains("import " + qualified)) {
                    continue;
                }
                int insertLine = ScalaCompletionProvider.importInsertLine(content);
                int offset = offsetOfLine(content, insertLine);
                Position at = new Position(insertLine, 0, offset);
                CodeAction action = new CodeAction();
                action.title = "导入 " + simpleName + "（" + qualified + "）";
                WorkspaceEdit edit = new WorkspaceEdit();
                edit.addChange(file, new TextEdit(new Range(at, at),
                        "import " + qualified + "\n"));
                action.workspaceEdit = edit;
                actions.add(action);
                return; // 一次只给一个 import 修复，避免列表里全是它
            }
        }
    }

    /**
     * 光标下这个标识符如果"在 classpath 里有、但文件没 import"，就给一条导入修复。
     *
     * <p>没有诊断上下文时（「生成代码」菜单）用它，效果和"长按报错处 → 添加 import"一样。
     */
    private void addImportFixAtCursor(List<CodeAction> actions, VirtualFile file, String content,
                                      DocumentRange documentRange) {
        int offset = offsetOfCursor(content, documentRange);
        if (offset < 0 || offset > content.length()) {
            return;
        }
        int start = offset;
        while (start > 0 && isIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        int end = offset;
        while (end < content.length() && isIdentifierPart(content.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return;
        }
        String name = content.substring(start, end);
        String qualified = ScalaTypeIndex.find(name);
        if (qualified == null || content.contains("import " + qualified)) {
            return;
        }
        int insertLine = ScalaCompletionProvider.importInsertLine(content);
        Position at = new Position(insertLine, 0, offsetOfLine(content, insertLine));
        CodeAction action = new CodeAction();
        action.title = "导入 " + name + "（" + qualified + "）";
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.addChange(file, new TextEdit(new Range(at, at), "import " + qualified + "\n"));
        action.workspaceEdit = edit;
        actions.add(action);
    }

    /** 光标在文档里的绝对偏移（优先用 index，没有就用行列换算）。 */
    private static int offsetOfCursor(String content, DocumentRange documentRange) {
        try {
            Range range = documentRange.range;
            if (range == null || range.start == null) {
                return -1;
            }
            if (range.start.index >= 0) {
                return range.start.index;
            }
            return offsetOfLine(content, range.start.line) + range.start.character;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c) && c != '$';
    }

    // ------------------------------------------------------------------ 工具

    private static int lineOf(DocumentRange documentRange) {
        try {
            Range range = documentRange.range;
            if (range == null || range.start == null) {
                return -1;
            }
            return range.start.line;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String lineText(String content, int line) {
        String[] lines = content.split("\n", -1);
        return line >= 0 && line < lines.length ? lines[line] : "";
    }

    private static String indentation(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static int offsetOfLine(String content, int line) {
        int offset = 0;
        int current = 0;
        while (current < line) {
            int next = content.indexOf('\n', offset);
            if (next < 0) {
                return content.length();
            }
            offset = next + 1;
            current++;
        }
        return offset;
    }

    /** 单独抽出来，方便判断"这行是不是带 extends 的类头"。 */
    static final class ClassHeader {
        private ClassHeader() {
        }

        static boolean matches(String line) {
            return line != null && CLASS_HEADER.matcher(line).matches();
        }
    }
}
