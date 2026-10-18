package com.scala.android.project;

import android.content.Context;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.baseModel.TextEdit;
import com.rapid.api.lang.engine.completion.CompletionItem;
import com.rapid.api.lang.engine.completion.CompletionItemKind;
import com.rapid.api.lang.engine.completion.CompletionParams;
import com.rapid.api.lang.engine.completion.CompletionProvider;
import com.rapid.api.lang.engine.completion.CompletionResult;
import com.rapid.api.lang.engine.completion.InsertTextFormat;
import com.rapid.framework.data.document.Position;
import com.rapid.framework.data.document.Range;
import com.scala.android.compiler.ScalaAnalysisApi;
import com.scala.android.tool.ScalaToolchain;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scala 补全：关键字 + 代码片段 + 当前文件声明 + classpath 类型（带自动导包）。
 *
 * <h3>类路径索引</h3>
 * 用 scala-android-compiler 里的 {@link ScalaClasspathIndexApi} 扫描
 * android.jar 与 scala-library，得到"简单类名 → 全限定名"的唯一映射，
 * 于是输入 {@code Buffered} 就能补出 {@code BufferedReader} 并自动补上
 * {@code import java.io.BufferedReader}。
 *
 * <p>扫描一个几十 MB 的 jar 要花时间，所以索引在后台线程里建，本次补全先用
 * 上一轮的索引；索引只跟 classpath 变化有关，建一次就一直用。
 */
public class ScalaCompletionProvider implements CompletionProvider {

    /** 前缀太短时不要用 classpath 类型刷屏。 */
    private static final int MIN_INDEX_PREFIX = 2;
    private static final int MAX_INDEX_ITEMS = 40;

    private static final List<String> KEYWORDS = Arrays.asList(
            "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export",
            "extends", "false", "final", "finally", "for", "given", "if", "implicit",
            "import", "inline", "lazy", "match", "new", "null", "object", "opaque", "open",
            "override", "package", "private", "protected", "return", "sealed", "super",
            "then", "this", "throw", "trait", "transparent", "true", "try", "type",
            "using", "val", "var", "while", "with", "yield"
    );

    private static final List<String> COMMON_TYPES = Arrays.asList(
            "String", "Int", "Long", "Double", "Float", "Boolean", "Unit", "Char", "Byte",
            "Short", "Any", "AnyRef", "Nothing", "Option", "Some", "None", "Either",
            "Array", "List", "Vector", "Seq", "Set", "Map", "Tuple2", "Future", "Try",
            "println", "print", "printf", "readLine", "require", "assert"
    );

    private static final Pattern DECLARATION = Pattern.compile(
            "\\b(?:object|class|trait|enum|def|val|var|given|type)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");


    @Override
    public void complete(CompletionParams params, CompletionResult result, CancellationToken token) {
        if (params == null || result == null) {
            return;
        }
        // 前缀自己从文档里算：宿主传的 partial 不一定可靠（点号后可能给成接收者），
        // 而这里只认"光标左边那段标识符"。
        String partial = effectivePartial(params);
        if (partial.isEmpty() && isPrecededByWhitespace(params) && !isAfterDot(params)) {
            // 空输入且刚敲了空格：用户是在排版，不是要补全。
            return;
        }

        // 点号之后只要"成员"：关键字/片段/类型在这里全是噪音。
        boolean afterDot = isAfterDot(params);
        Set<String> added = new HashSet<>();
        // 覆写骨架先加：同名成员如果在语义结果里先占位，插进去的就只是裸名字，
        // 而不是 "override def … = ???" 骨架。
        addOverrideMembers(result, params, partial, added);
        addSemanticTypes(result, params, partial, added);
        // build.sbt / plugins.sbt 是 Scala，但用到的键是 sbt DSL 的
        boolean sbtFile = isSbtFile(params);
        if (!afterDot && sbtFile) {
            addSbtSettings(result, partial, added);
        }
        if (!afterDot) {
            addSnippets(result, partial, added);
            addKeywords(result, partial, added);
            addDocumentSymbols(result, params, partial, added);
            addClasspathTypes(result, params, partial, added);
        }
    }

    @Override
    public void resolve(CompletionItem item) {
        if (item == null || item.label == null) {
            return;
        }
        if (KEYWORDS.contains(item.label)) {
            item.detail = "Scala 关键字";
        } else if (item.detail == null || item.detail.isEmpty()) {
            item.detail = "Scala";
        }
    }

    // ------------------------------------------------------------------ 片段

    private void addSnippets(CompletionResult result, String partial, Set<String> added) {
        // insertText 用 CodeSnippet 语法（${1:占位}、$0 最终光标位置）。
        addSnippet(result, partial, added, "main",
                "程序入口",
                "def main(args: Array[String]): Unit =\n  ${0:println(\"Hello, Scala!\")}");
        addSnippet(result, partial, added, "object",
                "顶层单例对象",
                "object ${1:Name}:\n  ${0}");
        addSnippet(result, partial, added, "class",
                "类定义",
                "class ${1:Name}(${2:val x: Int}):\n  ${0}");
        addSnippet(result, partial, added, "caseclass",
                "样例类",
                "case class ${1:Name}(${2:x: Int})");
        addSnippet(result, partial, added, "def",
                "方法定义",
                "def ${1:name}(${2}): ${3:Unit} =\n  ${0}");
        addSnippet(result, partial, added, "val",
                "不可变变量",
                "val ${1:name} = ${0}");
        addSnippet(result, partial, added, "var",
                "可变变量",
                "var ${1:name} = ${0}");
        addSnippet(result, partial, added, "if",
                "条件分支",
                "if ${1:condition} then\n  ${2}\nelse\n  ${0}");
        addSnippet(result, partial, added, "for",
                "for 表达式",
                "for ${1:item} <- ${2:items} do\n  ${0}");
        addSnippet(result, partial, added, "match",
                "模式匹配",
                "${1:value} match\n  case ${2:_} => ${0}");
        addSnippet(result, partial, added, "try",
                "异常处理",
                "try\n  ${1}\ncatch\n  case e: Exception => ${0}");
        addSnippet(result, partial, added, "println",
                "打印一行",
                "println(${0})");
        addSnippet(result, partial, added, "import",
                "导入语句",
                "import ${0}");
    }

    private void addSnippet(CompletionResult result, String partial, Set<String> added,
                            String label, String detail, String snippet) {
        if (!matches(partial, label) || !added.add(label)) {
            return;
        }
        CompletionItem item = new CompletionItem();
        item.label = label;
        item.detail = detail;
        item.insertText = snippet;
        item.insertTextFormat = InsertTextFormat.SNIPPET;
        item.kind = CompletionItemKind.Snippet;
        item.sortText = "0" + label;
        result.add(item);
    }

    // ------------------------------------------------------------- sbt 构建脚本

    /** 当前文件是不是 sbt 构建脚本（{@code build.sbt} / {@code project/*.sbt}）。 */
    private static boolean isSbtFile(CompletionParams params) {
        try {
            if (params == null || params.virtualFile == null) {
                return false;
            }
            String name = params.virtualFile.getName();
            return name != null && name.toLowerCase(Locale.ROOT).endsWith(".sbt");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** sbt 的 setting 键名（本插件解析器会读的那些，排在候选最前面）。 */
    private static final String[] SBT_KEYS = {
            "ThisBuild / scalaVersion", "ThisBuild / organization", "ThisBuild / version",
            "name", "organization", "version", "scalaVersion", "crossScalaVersions",
            "libraryDependencies", "resolvers", "scalacOptions", "javacOptions",
            "Compile / scalacOptions", "Compile / unmanagedJars", "Compile / unmanagedBase",
            // sbt-modern-android 的安卓键（本插件直接解释，不需要真的装 sbt 插件）
            "platformTarget", "androidApplicationId", "androidMinSdk", "androidTargetSdk",
            "androidVersionCode", "androidVersionName",
            "androidReleaseKeystore", "androidReleaseKeystoreAlias",
            "androidReleaseKeystorePassword", "androidReleaseKeyPassword"
    };

    /** sbt 运算符。 */
    private static final String[] SBT_OPERATORS = {":=", "+=", "++=", "-=", "%", "%%"};

    /** 常用 sbt 片段（insertText 用 CodeSnippet 语法）。 */
    private static final String[][] SBT_SNIPPETS = {
            {"libraryDependencies", "加一条依赖",
                    "libraryDependencies += \"${1:org.example}\" % \"${2:name}\" % \"${3:1.0.0}\"$0"},
            {"libraryDependencies++", "一次加多条依赖",
                    "libraryDependencies ++= Seq(\n  \"${1:org.example}\" % \"${2:name}\" % \"${3:1.0.0}\"$0\n)"},
            {"resolvers", "加一个依赖仓库",
                    "resolvers += \"${1:仓库名}\" at \"${2:https://.../}\"$0"},
            {"resolver-mavenCentral", "内置仓库（Maven Central）",
                    "resolvers += Resolver.mavenCentral$0"},
            {"scalaVersion", "设置 Scala 版本",
                    "scalaVersion := \"${1:3.8.4}\"$0"},
            {"enablePlugins", "启用 sbt 插件",
                    ".enablePlugins(${1:modernandroid.AndroidApp})$0"},
            {"settings", "追加工程设置",
                    ".settings(\n  ${1:name := \"app\"}$0\n)"},
            {"androidReleaseKeystore", "配置 release 签名密钥库",
                    "androidReleaseKeystore := Some(baseDirectory.value / \"${1:release-test.keystore}\")$0"},
            {"seq", "Seq(...)",
                    "Seq(\n  ${0}\n)"}
    };

    /** 在 .sbt 文件里补 sbt 的键 / 运算符 / 片段。 */
    private void addSbtSettings(CompletionResult result, String partial, Set<String> added) {
        for (String key : SBT_KEYS) {
            if (!matches(partial, key) || !added.add(key)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = key;
            item.detail = "sbt setting";
            item.insertText = key;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            item.kind = CompletionItemKind.Property;
            item.sortText = "0" + key;
            result.add(item);
        }
        for (String operator : SBT_OPERATORS) {
            if (!matches(partial, operator) || !added.add(operator)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = operator;
            item.detail = "sbt 运算符";
            item.insertText = operator;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            item.kind = CompletionItemKind.Operator;
            item.sortText = "0" + operator;
            result.add(item);
        }
        for (String[] snippet : SBT_SNIPPETS) {
            if (!matches(partial, snippet[0]) || !added.add(snippet[0])) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = snippet[0];
            item.detail = snippet[1];
            item.insertText = snippet[2];
            item.insertTextFormat = InsertTextFormat.SNIPPET;
            item.kind = CompletionItemKind.Snippet;
            item.sortText = "0" + snippet[0];
            result.add(item);
        }
    }

    // ---------------------------------------------------------------- 关键字

    private void addKeywords(CompletionResult result, String partial, Set<String> added) {
        for (String keyword : KEYWORDS) {
            if (!matches(partial, keyword) || !added.add(keyword)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = keyword;
            item.detail = "Scala 关键字";
            item.insertText = keyword;
            item.kind = CompletionItemKind.Keyword;
            item.sortText = "1" + keyword;
            result.add(item);
        }
        for (String type : COMMON_TYPES) {
            if (!matches(partial, type) || !added.add(type)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = type;
            item.detail = "Scala 常用符号";
            item.insertText = type;
            item.kind = CompletionItemKind.Class;
            item.sortText = "1" + type;
            result.add(item);
        }
    }

    // ------------------------------------------------------ 当前文件内的声明

    private void addDocumentSymbols(CompletionResult result, CompletionParams params,
                                    String partial, Set<String> added) {
        String content = params.content;
        if (content == null || content.isEmpty()) {
            return;
        }
        Matcher matcher = DECLARATION.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!matches(partial, name) || !added.add(name)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = name;
            item.detail = "当前文件中的声明";
            item.insertText = name;
            item.kind = CompletionItemKind.Variable;
            item.sortText = "2" + name;
            result.add(item);
        }
    }

    // -------------------------------------------------- 语义补全（默认开）

    /** 一次最多给出的语义候选数，避免点号后面刷出几百项拖慢弹窗。 */
    private static final int MAX_SEMANTIC_ITEMS = 80;

    /**
     * 消费上一次 dotty 交互式分析的结果，并提交下一轮分析。
     * 详见 {@link ScalaSemanticCompletion}：这条路径永远不会阻塞补全线程。
     */
    private void addSemanticTypes(CompletionResult result, CompletionParams params,
                                  String partial, Set<String> added) {
        ScalaSemanticCompletion semantic = ScalaSemanticCompletion.get();
        if (!semantic.isEnabled()) {
            return;
        }
        List<File> classpath = completionClasspath();
        List<ScalaAnalysisApi.Suggestion> suggestions = Collections.emptyList();
        try {
            if (params.virtualFile != null && params.position != null && params.content != null) {
                suggestions = semantic.completeNow(params.content, params.virtualFile.asURI(),
                        params.position.index, classpath);
            }
        } catch (Throwable ignored) {
            // 语义补全失败不能影响普通补全。
        }
        int count = 0;
        for (ScalaAnalysisApi.Suggestion suggestion : suggestions) {
            if (suggestion == null || suggestion.label == null || suggestion.label.isEmpty()) {
                continue;
            }
            if (!matches(partial, suggestion.label) || added.contains(suggestion.label)) {
                continue;
            }
            if (count >= MAX_SEMANTIC_ITEMS) {
                break;
            }
            CompletionItem item = new CompletionItem();
            item.label = suggestion.label;
            item.detail = suggestion.detail == null || suggestion.detail.isEmpty()
                    ? "Scala 作用域内符号" : suggestion.detail;
            // 方法自动补成 `name()`，并把光标放到括号里（$0 由宿主处理掉并定位）。
            String insertion = suggestion.label;
            if (suggestion.takesParams && isPlainIdentifier(insertion)) {
                insertion = insertion + "($0)";
            }
            item.insertText = insertion;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            item.kind = CompletionItemKind.Variable;
            // 语义结果比关键字/索引候选更贴近当前作用域，排最前。
            item.sortText = "0" + suggestion.label;
            result.add(item);
            added.add(suggestion.label);
            count++;
        }
    }

    // ------------------------------------------------ classpath 类型自动导包

    private void addClasspathTypes(CompletionResult result, CompletionParams params,
                                   String partial, Set<String> added) {
        if (partial.length() < MIN_INDEX_PREFIX) {
            return;
        }
        Map<String, String> types = ScalaTypeIndex.types();
        if (types.isEmpty()) {
            triggerIndex();
            return;
        }
        String lowerPrefix = partial.toLowerCase(Locale.ROOT);
        int insertLine = importInsertLine(params.content);
        String content = params.content == null ? "" : params.content;
        int count = 0;
        for (Map.Entry<String, String> entry : types.entrySet()) {
            String simpleName = entry.getKey();
            String qualifiedName = entry.getValue();
            if (count >= MAX_INDEX_ITEMS) {
                break;
            }
            if (!simpleName.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                continue;
            }
            if (added.contains(simpleName) || isDefaultImported(qualifiedName)) {
                continue;
            }
            if (content.contains("import " + qualifiedName)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = simpleName;
            item.detail = "自动导入 " + qualifiedName;
            item.insertText = simpleName;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            item.kind = CompletionItemKind.Class;
            item.sortText = "3" + simpleName;
            item.additionalTextEdits = Collections.singletonList(
                    new TextEdit(new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                            "import " + qualifiedName + "\n"));
            result.add(item);
            added.add(simpleName);
            count++;
        }
        triggerIndex();
    }

    /** 后台（重新）构建 classpath 索引；索引与"添加 import"快速修复共用（ScalaTypeIndex）。 */
    private void triggerIndex() {
        ScalaTypeIndex.prefetch(completionClasspath());
    }

    // ------------------------------------------------------------------ 工具

    private static boolean matches(String partial, String candidate) {
        if (partial == null || partial.isEmpty()) {
            return true;
        }
        return candidate.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT));
    }

    private static boolean isPrecededByWhitespace(CompletionParams params) {
        String lineText = params.lineText;
        int column = params.position == null ? -1 : params.position.character;
        if (lineText == null || column <= 0 || column > lineText.length()) {
            return false;
        }
        return Character.isWhitespace(lineText.charAt(column - 1));
    }

    /**
     * 点号之后只给"成员"候选。
     *
     * <p>判断的是<b>当前这段标识符前面</b>是不是 {@code .}，所以 {@code Toast.}
     * （标识符为空）与 {@code Toast.m} 都算成员位置。
     */
    private static boolean isAfterDot(CompletionParams params) {
        String content = params.content;
        int offset = params.position == null ? -1 : params.position.index;
        if (content == null || offset <= 0 || offset > content.length()) {
            return false;
        }
        int start = wordStart(content, offset);
        return start > 0 && content.charAt(start - 1) == '.';
    }

    /** 光标左边那段标识符的起点。 */
    private static int wordStart(String content, int offset) {
        int index = Math.min(offset, content.length());
        while (index > 0) {
            char c = content.charAt(index - 1);
            if (Character.isJavaIdentifierPart(c) && c != '$') {
                index--;
            } else {
                break;
            }
        }
        return index;
    }

    /**
     * 自己从文档里取补全前缀。
     *
     * <p>宿主传下来的 {@code params.partial} 不可靠（点号后可能给成接收者的名字），
     * 以前"点号后弹窗里只有 Toast、再敲一个字母才出方法"就是被它带偏的。
     */
    private static String effectivePartial(CompletionParams params) {
        String content = params.content;
        int offset = params.position == null ? -1 : params.position.index;
        if (content != null && offset > 0 && offset <= content.length()) {
            return content.substring(wordStart(content, offset), offset);
        }
        return params.partial == null ? "" : params.partial;
    }

    /** 补全用的 classpath（标准库 + android.jar + 工程依赖，含 lib/ 里的 studio-api）。 */
    private List<File> completionClasspath() {
        try {
            Context context = AndroidPlugins.getContext(this);
            if (context != null) {
                return ScalaToolchain.compileClasspath(context);
            }
        } catch (Throwable ignored) {
            // 拿不到就当没有：语义候选会少，但不能影响关键字/片段补全。
        }
        return Collections.emptyList();
    }

    /**
     * 类/特质体里的"覆写成员"候选。
     *
     * <p>继承或实现接口后（{@code class A extends BaseDomainMenuAction}），在这里输入
     * {@code get} 就能看到 {@code getTitle / getId …}，选中后直接插入
     * {@code override def getTitle: String = } 骨架，不用自己记方法签名。
     */
    private void addOverrideMembers(CompletionResult result, CompletionParams params,
                                    String partial, Set<String> added) {
        String content = params.content;
        int offset = params.position == null ? -1 : params.position.index;
        if (content == null || offset < 0) {
            return;
        }
        if (!ScalaOverrideMembers.isInheritanceContext(content, offset)) {
            return;
        }
        int count = 0;
        for (ScalaOverrideMembers.Item item
                : ScalaOverrideMembers.items(content, offset, completionClasspath())) {
            if (count >= MAX_OVERRIDE_ITEMS || !matches(partial, item.name)
                    || !added.add(item.name)) {
                continue;
            }
            CompletionItem completion = new CompletionItem();
            completion.label = item.name;
            completion.detail = item.signature;
            completion.insertText = item.insertText;
            completion.insertTextFormat = InsertTextFormat.SNIPPET;
            completion.kind = CompletionItemKind.Method;
            completion.sortText = item.sortText;
            result.add(completion);
            count++;
        }
    }

    /** 覆写候选最多给几条。 */
    private static final int MAX_OVERRIDE_ITEMS = 40;

    /**
     * 普通标识符才自动补括号；运算符方法（{@code ++} / {@code ==} 等）不加，
     * 因为 {@code xs.++()} 这种写法没有意义。
     */
    private static boolean isPlainIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** import 语句应该插到哪一行（package / 已有 import 之后）。 */
    /** 快速修复（添加 import）也用它：import 该插到哪一行。 */
    static int importInsertLine(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String[] lines = content.split("\n", -1);
        int insertLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("package ")) {
                insertLine = i + 1;
                continue;
            }
            if (trimmed.startsWith("import ")) {
                insertLine = i + 1;
                continue;
            }
            break;
        }
        return insertLine;
    }

    private static boolean isDefaultImported(String qualifiedName) {
        if (qualifiedName.startsWith("java.lang.")) {
            return true;
        }
        if (qualifiedName.startsWith("scala.")) {
            return qualifiedName.indexOf('.', "scala.".length()) < 0;
        }
        return qualifiedName.startsWith("scala.Predef.");
    }

    private static String join(List<File> files) {
        StringBuilder builder = new StringBuilder();
        for (File file : files) {
            if (builder.length() > 0) {
                builder.append(File.pathSeparatorChar);
            }
            builder.append(file.getAbsolutePath());
        }
        return builder.toString();
    }

    /** 保留：方便以后按需展示去重后的候选名。 */
    @SuppressWarnings("unused")
    private static Set<String> uniqueNames(Map<String, String> types) {
        return new LinkedHashSet<>(types.keySet());
    }
}
