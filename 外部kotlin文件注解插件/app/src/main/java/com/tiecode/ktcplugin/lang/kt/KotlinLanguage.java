package com.tiecode.ktcplugin.lang.kt;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.Reader;

import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

/**
 * Kotlin 语言：负责<b>语法高亮</b>（TextMate 语法）与<b>符号配对</b>，
 * 补全等语义能力由 {@link KotlinLanguageEngine} 通过结绳语言引擎通道提供。
 *
 * <p>语法取自随插件分发的 {@code textmate/kotlin/Kotlin.tmLanguage.json}
 * （scope {@code source.kotlin}），因此不依赖结绳宿主是否自带 Kotlin 语法。
 *
 * <p>这里额外重写了两个<b>触发钩子</b>：
 * {@link #needsCompletion(ContentReference, CharPosition)} 与
 * {@link #isCompletionChar(char)}。原因是 {@code TextMateLanguage} 继承自
 * sora 的 {@code EmptyLanguage}，对这两个方法的默认实现偏保守；而编辑器
 * （以及结绳的语言引擎分发）会用它们判断「这次输入要不要去问语言引擎」。
 * 不重写的话，即使 {@link KotlinCompletionProvider} 注册正确，也可能根本
 * 不会被问到——表现为补全框不弹。参考实现是同一工作区里的
 * {@code GoLanguage}，它同样把 {@code .} 纳入触发字符。
 */
public class KotlinLanguage extends TextMateLanguage {

    /** 语言 id：必须与 {@code KotlinLanguageFactory.getLanguageId()} 及引擎注册键一致。 */
    public static final String ID = "kotlin";

    /** 显示名（模型符、状态栏、语言列表会用到）。 */
    public static final String NAME = "Kotlin";

    public KotlinLanguage(IGrammarSource grammarSource, Reader languageConfiguration,
                          IThemeSource themeSource) {
        super(grammarSource, languageConfiguration, themeSource, true);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * 是否需要在光标处提供补全。
     *
     * <p>Kotlin 的补全包含工程内符号与标准库，本地判断「该不该补」价值不大，
     * 真正的过滤交给 {@link KotlinCompletionProvider}。统一返回 true。
     *
     * <p>说明：当前宿主实际使用的闸门是 {@link #isCompletionChar(char)}
     * （见 {@code LanguageEngineDriver.createCompletionParams}），本方法暂无调用者；
     * 保留覆写是为了兼容仍会询问它的 sora / 结绳版本。
     */
    @Override
    public boolean needsCompletion(ContentReference content, CharPosition position) {
        return content != null && position != null;
    }

    /**
     * 哪些字符会触发补全。
     *
     * <p><b>这是宿主真正的补全闸门</b>：{@code LanguageEngineDriver.createCompletionParams}
     * 用它判断「这个字符之后要不要去问语言引擎」，不通过就直接不发起补全请求。
     * 因此在父类认可的字符之外，额外纳入 Kotlin 里最常见的触发场景：
     * <ul>
     *   <li>{@code .} —— 成员/包名补全（{@code list.}、{@code kotlin.}）；</li>
     *   <li>{@code _}、{@code $} 与字母数字 —— 标识符补全（输入 {@code fun} 就想看到 {@code fun}）。</li>
     * </ul>
     */
    @Override
    public boolean isCompletionChar(char ch) {
        return ch == '.' || ch == '_' || ch == '$'
                || Character.isLetterOrDigit(ch)
                || super.isCompletionChar(ch);
    }
}
