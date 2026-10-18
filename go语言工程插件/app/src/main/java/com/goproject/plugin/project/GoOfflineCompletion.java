package com.goproject.plugin.project;

import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.completion.CompletionItem;
import com.rapid.api.lang.engine.completion.CompletionItemKind;
import com.rapid.api.lang.engine.completion.CompletionParams;
import com.rapid.api.lang.engine.completion.CompletionProvider;
import com.rapid.api.lang.engine.completion.CompletionResult;
import java.util.List;
import java.util.Arrays;

/**
 * 插件自带的离线补全：Go 关键字、语法片段、常用标准库函数。
 *
 * <p>它的定位是<b>兜底</b>：gopls 可用时优先用 gopls 的语义补全，
 * 只有连不上 gopls（没启动 / 端口不对 / 没有 go.mod）时才走这里，
 * 保证插件在离线状态下依然能弹补全框。
 *
 * <p>内容与插件原实现一致，未做改动。
 */
public class GoOfflineCompletion implements CompletionProvider {

    // 1. Go语言核心关键字（按使用频率排序）
    private static final List<String> GO_KEYWORDS = Arrays.asList(
        "package", "import", "func", "var", "const", "type", "struct",
        "interface", "if", "else", "for", "range", "switch", "case", "default",
        "break", "continue", "return", "go", "select", "chan", "map", "slice",
        "int", "string", "bool", "float64", "error", "nil", "true", "false"
    );

    // 2. Go常用语法快捷片段
    private static final List<SnippetItem> GO_SNIPPETS = Arrays.asList(
        new SnippetItem("func", "func () {\n\t$0\n}", "函数定义"),
        new SnippetItem("init", "func init() {\n\t$0\n}", "init函数"),
        new SnippetItem("main", "func main() {\n\t$0\n}", "main函数"),
        new SnippetItem("struct", "type $1 struct {\n\t$0\n}", "定义结构体"),
        // fori：补全 传统索引循环（最常用，适配Go基础遍历）
        new SnippetItem("fori", "for i := 0; i < len($1); i++ {\n\t$0\n}", "快捷代码"),
        new SnippetItem("forr", "for $1 := range $2 {\n\t$0\n}", "for-range循环（遍历切片/Map）"),
        new SnippetItem("if", "if $1 {\n\t$0\n} else {\n\t\n}", "if-else判断"),
        new SnippetItem("import", "import \"$0\"", "导入依赖包"),
        new SnippetItem("imports", "import (\n\t\"$0\"\n)", "多包导入"),
        new SnippetItem("var", "var $1 $2 = $0", "变量声明并赋值"),
        new SnippetItem("const", "const $1 = $0", "常量声明"),
        new SnippetItem("interface", "type $1 interface {\n\t$0\n}", "定义接口")
    );

    // 3. Go标准库高频函数补全（重点：fmt、json、os等，支持 fmt.Println 这类带包名的函数）
    private static final List<GoFunctionItem> GO_STD_FUNCTIONS = Arrays.asList(
        // fmt包（最常用）
        new GoFunctionItem("fmt.Println", "打印内容到控制台，自动换行", "fmt.Println($0)"),
        new GoFunctionItem("fmt.Print", "打印内容到控制台，不换行", "fmt.Print($0)"),
        // json包（序列化/反序列化）
        new GoFunctionItem("json.Marshal", "将结构体序列化为JSON字节流", "json.Marshal($0)"),
        new GoFunctionItem("json.Unmarshal", "将JSON字节流反序列化为结构体", "json.Unmarshal($1, &$0)"),
        // os包（系统操作）
        new GoFunctionItem("os.Exit", "退出程序", "os.Exit($0)"),
        new GoFunctionItem("os.Getwd", "获取当前工作目录", "os.Getwd()")
    );

    @Override
    public void complete(CompletionParams params, CompletionResult result, CancellationToken token) {
        if (params == null || result == null) {
            return;
        }
        String partial = params.partial;
        if (partial == null || partial.trim().isEmpty()) {
            return; // 空输入不触发补全
        }
        partial = partial.toLowerCase(); // 忽略大小写匹配（fmt/PRINTLN 均能匹配）

        // 优先级：1. 标准库函数补全（最常用，优先显示）
        for (GoFunctionItem func : GO_STD_FUNCTIONS) {
            if (func.name.toLowerCase().startsWith(partial)) {
                result.add(snippet(func.name, func.snippetContent, func.desc + "（快捷代码）"));
            }
        }

        // 优先级：2. 语法快捷片段
        for (SnippetItem snippet : GO_SNIPPETS) {
            if (snippet.name.toLowerCase().startsWith(partial)) {
                result.add(snippet(snippet.name, snippet.content, snippet.desc));
            }
        }

        // 优先级：3. Go关键字补全
        for (String keyword : GO_KEYWORDS) {
            if (keyword.toLowerCase().startsWith(partial)) {
                result.add(keyword(keyword, getKeywordDescription(keyword)));
            }
        }
    }

    @Override
    public void resolve(CompletionItem completionItem) {
        // 选中补全项后，完善详情（显示函数用法/关键字说明）
        String label = completionItem.label.toLowerCase();
        // 优先匹配函数描述
        for (GoFunctionItem func : GO_STD_FUNCTIONS) {
            if (func.name.toLowerCase().equals(label)) {
                completionItem.detail = func.desc + " | 用法：" + func.snippetContent;
                return;
            }
        }
        // 匹配关键字描述
        completionItem.detail = getKeywordDescription(label);
    }

    // 关键字描述（优化补充）
    private String getKeywordDescription(String keyword) {
        switch (keyword.toLowerCase()) {
            case "package": return "包声明";
            case "import": return "关键字";
            case "func": return "关键字";
            case "var": return "变量声明";
            case "const": return "常量声明";
            case "type": return "类型定义";
            case "struct": return "结构体";
            case "interface": return "接口";
            case "go": return "启动协程（并发执行）";
            case "chan": return "通道（协程间通信）";
            case "map": return "映射（键值对集合）";
            case "slice": return "切片（动态数组）";
            case "range": return "遍历（切片/Map/通道等）";
            default: return "关键字";
        }
    }

    // 关键字补全项构建（修正为Keyword类型，更规范）
    private CompletionItem keyword(String type, String desc) {
        CompletionItem item = new CompletionItem();
        item.label = type;
        item.detail = desc;
        item.insertText = type;
        item.kind = CompletionItemKind.Keyword;
        return item;
    }

    // 语法片段/函数片段补全项构建
    private CompletionItem snippet(String name, String content, String desc) {
        CompletionItem item = new CompletionItem();
        item.label = name;
        item.detail = desc;
        item.insertText = content;
        item.kind = CompletionItemKind.Snippet;
        return item;
    }

    // 语法片段封装类
    private static class SnippetItem {
        String name;
        String content;
        String desc;

        public SnippetItem(String name, String content, String desc) {
            this.name = name;
            this.content = content;
            this.desc = desc;
        }
    }

    // Go标准库函数封装类（统一管理函数名、描述、模板）
    private static class GoFunctionItem {
        String name; // 函数名（带包名，如 fmt.Println）
        String desc; // 函数描述（便于用户理解）
        String snippetContent; // 函数快捷模板（插入后可直接编辑）

        public GoFunctionItem(String name, String desc, String snippetContent) {
            this.name = name;
            this.desc = desc;
            this.snippetContent = snippetContent;
        }
    }
}
