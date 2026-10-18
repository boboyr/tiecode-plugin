package com.linux.plugin.lang.go;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.Reader;

import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

/**
 * Go 语言：只负责<b>语法高亮与符号配对</b>，语义能力（补全、跳转、诊断……）
 * 全部由 gopls 通过 LSP 通道提供。
 *
 * <p>语法取自 vscode 内置的 Go TextMate 语法（scope {@code source.go}），
 * 随插件 assets 一起分发，因此不依赖结绳宿主是否自带 Go 语法。
 *
 * <p>这里额外重写了两个<b>触发钩子</b>（{@code needsCompletion} /
 * {@code isCompletionChar}）。原因是 {@code TextMateLanguage} 继承自
 * Sora 的 {@code EmptyLanguage} 且并未重写它们，默认行为偏保守；
 * 而编辑器（以及结绳的语言引擎分发）会用这两个方法判断
 * "这次输入要不要去问语言引擎"。不重写的话，即使 LSP 引擎注册正确，
 * 也可能根本不会被问到——表现为补全框不弹。
 * 参考实现是同一工程里的 {@code TieLanguage}，它同样重写了
 * {@code isCompletionChar} 并把 {@code .} 纳入触发字符。
 */
public class GoLanguage extends TextMateLanguage {

    /** 语言 id：必须与 {@code GoLanguageFactory.getLanguageId()} 以及注册表里的键一致。 */
    public static final String ID = "go";

    /** assets 内语法文件的位置，同时被本类与工厂引用。 */
    public static final String GRAMMAR_ASSET = "textmate/go/go.tmLanguage.json";
    public static final String CONFIGURATION_ASSET = "textmate/go/language-configuration.json";

    public GoLanguage(IGrammarSource grammarSource, Reader languageConfiguration, IThemeSource themeSource) {
        super(grammarSource, languageConfiguration, themeSource, true);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Go";
    }

    /**
     * 是否需要在光标处提供补全。
     *
     * <p>Go 的补全请求交给 gopls，本地判断"该不该补"几乎没有价值：
     * 真正的判定需要类型信息，那正是服务端的活。所以这里统一返回 true，
     * 让每次编辑都可能问到引擎；具体的过滤由 gopls 完成。
     */
    @Override
    public boolean needsCompletion(ContentReference content, CharPosition position) {
        return content != null && position != null;
    }

    /**
     * 哪些字符会触发补全。
     *
     * <p>除了沿用父类认可的字符，额外纳入 Go 里最常见的两个触发场景：
     * <ul>
     *   <li>{@code .} —— 选择器补全（{@code fmt.} / {@code msg.}），最常见；</li>
     *   <li>{@code _} 与字母数字 —— 标识符补全（输入 {@code f} 就想看到 {@code fmt}）。</li>
     * </ul>
     */
    @Override
    public boolean isCompletionChar(char ch) {
        return ch == '.' || ch == '_' || Character.isLetterOrDigit(ch) || super.isCompletionChar(ch);
    }
}
