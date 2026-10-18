package com.tiecode.ktcplugin.lang.kt;

import android.util.Log;

import com.rapid.api.lang.engine.AbstractProvider;
import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.baseModel.TextEdit;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.completion.CompletionItem;
import com.rapid.api.lang.engine.completion.CompletionItemKind;
import com.rapid.api.lang.engine.completion.CompletionParams;
import com.rapid.api.lang.engine.completion.CompletionProvider;
import com.rapid.api.lang.engine.completion.CompletionResult;
import com.rapid.api.lang.engine.completion.InsertTextFormat;
import com.rapid.framework.data.document.Position;
import com.rapid.framework.data.document.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kotlin 补全提供者。
 *
 * <p>结果由四部分拼成，都<b>离线可用</b>，不依赖 kotlinc 或语言服务器：
 * <ol>
 *   <li><b>语法片段</b>：{@code fun} / {@code class} / {@code when} / {@code when} 等常用结构，
 *       插入 LSP Snippet，展开后可直接按 Tab 跳占位符（见 {@link KotlinSnippets#snippets()}）；</li>
 *   <li><b>关键字</b>：{@code val} / {@code var} / {@code suspend} …（{@link KotlinSnippets#keywords()}）；</li>
 *   <li><b>标准库</b>：{@code listOf} / {@code map} / {@code String} / {@code @JvmStatic} 等
 *       常用类型与函数（{@link KotlinStdlib#all()}）；</li>
 *   <li><b>classpath 类型</b>：从 android.jar / 工具链 jar 扫出的类型索引，
 *       <b>选中即自动补 import</b>（见 {@link KotlinClasspathIndex}）；</li>
 *   <li><b>工程符号</b>：{@link KotlinSourceIndex} 扫到的工程内类、函数、属性，
 *       外加当前编辑文档里出现过的标识符（就近补全）。</li>
 * </ol>
 *
 * <h3>成员访问</h3>
 * <p>输入 {@code .} 时不去猜成员类型（那需要真正的语义分析），因此一律不倒
 * 关键字与语法片段——在 {@code obj.} 后面弹一堆 {@code val} / {@code class} 只会吵。
 * 标准库也只在用户已经敲了至少一个字母时参与（{@code list.li} 这种前缀过滤是有用的；
 * 而光秃秃一个 {@code .} 后面列出 258 个类型名，只会把真正想要的成员挤出视野）。
 * 工程符号与当前文档标识符则始终参与：它们最可能就是该对象的成员。
 *
 * <p>过滤规则是<b>前缀匹配</b>（大小写不敏感）。不用「包含匹配」，因为那会让
 * 输入一两个字母就命中上百项，候选框反而不可用。
 */
public class KotlinCompletionProvider extends AbstractProvider<DefaultLanguageContext>
        implements CompletionProvider {

    private static final String TAG = "KotlinLang";

    /** 单个候选集上限，避免超长文件把补全框撑爆。 */
    private static final int MAX_ITEMS = 400;

    /** 前缀太短时不要用 classpath 类型刷屏（与 scala 插件同为 2）。 */
    private static final int MIN_INDEX_PREFIX = 2;

    /** 单次补全最多给出多少个「自动导入」候选。 */
    private static final int MAX_IMPORT_ITEMS = 40;

    /** 工程符号索引（带缓存，见 {@link KotlinSourceIndex}）。 */
    private final KotlinSourceIndex index = KotlinSourceIndex.get();

    /** classpath 类型索引（用于自动导包，见 {@link KotlinClasspathIndex}）。 */
    private final KotlinClasspathIndex classpathIndex = KotlinClasspathIndex.get();

    public KotlinCompletionProvider(DefaultLanguageContext context) {
        super(context);
    }

    @Override
    public void complete(CompletionParams params, CompletionResult result, CancellationToken token) {
        if (params == null || result == null) {
            return;
        }
        if (token != null && token.isCancellationRequested) {
            return;
        }

        String prefix = KotlinTextUtils.prefixOf(params);
        // 接收者既用于判断「是不是成员访问」，也用于给候选排序。
        String receiver = KotlinTextUtils.receiverOf(params);
        boolean memberAccess = params.triggerChar == '.' || receiver != null;

        // 「光标前以空白结尾」的位置不弹补全：用户刚敲了空格/换行，是在排版或写下一个词，
        // 不是在挑候选；不挡的话「空前缀给全部候选」会让补全框一次次糊上来，正好盖住输入位置。
        //
        // 为什么这条必须有：Sora 的 EditorAutoCompletion 对「任何插入」都会发起补全，
        // 结绳那层 isCompletionChar 闸门在输入法组合态下会判到组合文本而不是刚敲的空格，
        // 于是空格也能漏进来。这里以「光标前的真实文本」为准，是最后一道防线。
        //
        // 两个例外仍然给候选，因为那里确实是在写代码：
        //   1) 成员访问（`list.`）——语义明确；
        //   2) 未闭合的括号内部（`foo(`、`foo(a, `）——正要写下一个参数。
        if (prefix.isEmpty() && !memberAccess
                && KotlinTextUtils.endsWithWhitespace(params)
                && !KotlinTextUtils.isInsideUnclosedBracket(params)) {
            Log.d(TAG, "空白位置不提供补全: line=[" + params.lineText + "] partial=["
                    + params.partial + "] trigger='" + params.triggerChar + "'");
            return;
        }

        Set<String> added = new HashSet<>();

        if (!memberAccess) {
            // 1) 语法片段（排最前）
            addSnippets(prefix, result, added, token);
            // 2) 关键字
            addKeywords(prefix, result, added, token);
        }

        // 3) classpath 类型（选中即自动补 import）；成员访问时不掺进来。
        if (!memberAccess) {
            addClasspathTypes(params, prefix, result, added, token);
        }

        // 4) 标准库；成员访问且还没输入字母时跳过，避免用 258 个类型名刷屏。
        if (!memberAccess || !prefix.isEmpty()) {
            addStdlib(prefix, result, added, token);
        }
        // 5) 工程符号
        addIndexedSymbols(prefix, receiver, result, added, token);
        // 6) 当前文档里出现过的标识符（就近补全）
        addDocumentWords(params, prefix, result, added, token);
    }

    @Override
    public void resolve(CompletionItem completionItem) {
        // 所有信息（label / detail / insertText）在 complete 阶段已完整生成。
    }

    /** 宿主用同一个 token 取消上一次补全；扫描/枚举过程中也应尽快退出。 */
    private static boolean isCancelled(CancellationToken token) {
        return token != null && token.isCancellationRequested;
    }

    // ------------------------------------------------------------------
    // 各来源
    // ------------------------------------------------------------------

    private void addSnippets(String prefix, CompletionResult result, Set<String> added,
                             CancellationToken token) {
        for (String[] row : KotlinSnippets.snippets()) {
            if (isCancelled(token)) {
                return;
            }
            String label = row[0];
            if (!KotlinTextUtils.matches(label, prefix) || !added.add(label)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = label;
            item.symbolName = label;
            item.kind = CompletionItemKind.Snippet;
            item.detail = row[1];
            item.sortText = "0_" + label;
            item.insertText = row[2];
            item.insertTextFormat = InsertTextFormat.SNIPPET;
            result.add(item);
        }
    }

    private void addKeywords(String prefix, CompletionResult result, Set<String> added,
                             CancellationToken token) {
        for (String[] row : KotlinSnippets.keywords()) {
            if (isCancelled(token)) {
                return;
            }
            String label = row[0];
            if (!KotlinTextUtils.matches(label, prefix) || !added.add(label)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = label;
            item.symbolName = label;
            item.kind = CompletionItemKind.Keyword;
            item.detail = "Kotlin 关键字 · " + row[1];
            item.sortText = "1_" + label;
            item.insertText = label;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            result.add(item);
        }
    }

    /**
     * classpath 类型补全 + <b>自动导包</b>。
     *
     * <p>输入 {@code Acti} 时给出 {@code Activity}，并把
     * {@code import android.app.Activity} 作为 {@code additionalTextEdits}
     * 一起提交——用户选中候选后，import 会同时出现在文件顶部。
     * 这是 scala 控制台插件 {@code addClasspathTypes} 的同款做法。
     *
     * <p>只做前缀至少 {@value #MIN_INDEX_PREFIX} 个字符的情况：一个字母就列出几十个
     * 需要导包的类名，只会把真正想输的东西挤没。已经是默认导入（{@code java.lang.*}、
     * {@code kotlin.*}）或文件里已写过 import 的类型，只给候选、不再插 import。
     */
    private void addClasspathTypes(CompletionParams params, String prefix,
                                   CompletionResult result, Set<String> added,
                                   CancellationToken token) {
        // 索引在后台建，这里从不等待；没建好就顺手触发一次。
        classpathIndex.ensureBuilt();
        Map<String, String> types = classpathIndex.snapshot();
        if (types.isEmpty() || prefix.length() < MIN_INDEX_PREFIX) {
            return;
        }
        String content = params.content == null ? "" : params.content;
        int insertLine = KotlinTextUtils.importInsertLine(content);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        int count = 0;
        for (Map.Entry<String, String> entry : types.entrySet()) {
            if (count >= MAX_IMPORT_ITEMS || isCancelled(token)) {
                return;
            }
            String simpleName = entry.getKey();
            String qualifiedName = entry.getValue();
            if (!simpleName.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                continue;
            }
            // 已经被片段/关键字/标准库占用的标签就不再重复（同名以先加入的为准）。
            if (!added.add(simpleName)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = simpleName;
            item.symbolName = simpleName;
            item.kind = CompletionItemKind.Class;
            item.insertText = simpleName;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            boolean needsImport = !KotlinTextUtils.isDefaultImported(qualifiedName)
                    && !KotlinTextUtils.hasImport(content, qualifiedName);
            item.detail = needsImport
                    ? "自动导入 " + qualifiedName
                    : qualifiedName;
            item.sortText = (needsImport ? "2a_" : "2b_") + simpleName;
            if (needsImport) {
                item.additionalTextEdits = Collections.singletonList(new TextEdit(
                        new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                        "import " + qualifiedName + "\n"));
            }
            result.add(item);
            count++;
            if (result.size() >= MAX_ITEMS) {
                result.isIncomplete = true;
                return;
            }
        }
    }

    private void addStdlib(String prefix, CompletionResult result, Set<String> added,
                           CancellationToken token) {
        for (KotlinStdlib.Entry entry : KotlinStdlib.all()) {
            if (isCancelled(token)) {
                return;
            }
            if (!KotlinTextUtils.matches(entry.label, prefix) || !added.add(entry.label)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = entry.label;
            item.symbolName = entry.label;
            item.kind = entry.kind;
            item.detail = "Kotlin 标准库 · " + entry.detail;
            item.sortText = (entry.kind == CompletionItemKind.Class ? "2_" : "3_") + entry.label;
            item.insertText = entry.label;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            result.add(item);
            if (result.size() >= MAX_ITEMS) {
                result.isIncomplete = true;
                return;
            }
        }
    }

    private void addIndexedSymbols(String prefix, String receiver, CompletionResult result,
                                   Set<String> added, CancellationToken token) {
        List<KotlinSourceIndex.Symbol> symbols;
        try {
            symbols = index.snapshot();
        } catch (Throwable t) {
            return;
        }
        // 成员访问时优先给同名接收者相关的候选：没有类型信息，只能做「名字相同」
        // 这种弱关联。优先级靠 sortText 前缀体现（宿主按 sortText 升序排），
        // 只靠遍历顺序是没用的——排序那一步会把顺序冲掉。
        List<KotlinSourceIndex.Symbol> ordered = new ArrayList<>(symbols.size());
        if (receiver != null) {
            for (KotlinSourceIndex.Symbol symbol : symbols) {
                if (symbol.name.equals(receiver)) {
                    ordered.add(symbol);
                }
            }
        }
        for (KotlinSourceIndex.Symbol symbol : symbols) {
            if (receiver == null || !symbol.name.equals(receiver)) {
                ordered.add(symbol);
            }
        }
        for (KotlinSourceIndex.Symbol symbol : ordered) {
            if (isCancelled(token)) {
                return;
            }
            if (!KotlinTextUtils.matches(symbol.name, prefix) || !added.add(symbol.name)) {
                continue;
            }
            boolean nearReceiver = receiver != null && receiver.equals(symbol.name);
            CompletionItem item = new CompletionItem();
            item.label = symbol.name;
            item.symbolName = symbol.name;
            item.kind = symbol.kind;
            item.detail = "工程符号 · " + symbol.detail;
            // 4a_ 与接收者同名（弱关联、优先），4b_ 其余工程符号。
            item.sortText = (nearReceiver ? "4a_" : "4b_") + symbol.name;
            item.insertText = symbol.name;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            result.add(item);
            if (result.size() >= MAX_ITEMS) {
                result.isIncomplete = true;
                return;
            }
        }
    }

    private void addDocumentWords(CompletionParams params, String prefix, CompletionResult result,
                                  Set<String> added, CancellationToken token) {
        String content = params.content;
        if ((content == null || content.isEmpty()) && context != null && params.virtualFile != null) {
            try {
                content = context.getDynamicContent(params.virtualFile);
            } catch (Throwable ignored) {
            }
        }
        if (content == null || content.isEmpty()) {
            return;
        }
        for (String word : KotlinSourceIndex.wordsOf(content)) {
            if (isCancelled(token)) {
                return;
            }
            if (!KotlinTextUtils.matches(word, prefix) || !added.add(word)) {
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.label = word;
            item.symbolName = word;
            item.kind = CompletionItemKind.Text;
            item.detail = "当前文件";
            item.sortText = "5_" + word;
            item.insertText = word;
            item.insertTextFormat = InsertTextFormat.PLAINTEXT;
            result.add(item);
            if (result.size() >= MAX_ITEMS) {
                result.isIncomplete = true;
                return;
            }
        }
    }
}
