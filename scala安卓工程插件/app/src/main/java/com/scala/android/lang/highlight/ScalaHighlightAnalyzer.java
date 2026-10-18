package com.scala.android.lang.highlight;

import com.rapid.android.ui.widget.editor.highlight.DecisionBaseAnalyzer;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import com.scala.android.lang.ScalaHighlightState;
import com.scala.android.lang.ScalaTokenizer;

/**
 * Scala 语法高亮分析器：先切词，再按决策规则着色。
 *
 * <p>走结绳自己的 {@code DecisionBaseAnalyzer} 通道（与结绳语言、Linux 插件
 * 的语言实现一致），不依赖 TextMate —— TextMate 语法在宿主里需要
 * {@code TextMateLanguage} 与 tm4e 的语法/主题联动，插件侧不好保证生效。
 */
public class ScalaHighlightAnalyzer extends DecisionBaseAnalyzer<ScalaHighlightState> {

    public ScalaHighlightAnalyzer() {
        addDecision(new ScalaIdentifierDecision());
        addDecision(new ScalaBasicDecision());
    }

    @Override
    public TokenModel getTokenModel(CharSequence content) {
        return new ScalaTokenizer(content).tokenize();
    }

    @Override
    public ScalaHighlightState getDecisionEnv() {
        return new ScalaHighlightState();
    }
}
