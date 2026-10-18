package com.linux.plugin.lang.engine.highlight;

import com.rapid.android.ui.widget.editor.highlight.DecisionBaseAnalyzer;
import com.rapid.api.lang.engine.highlight.token.TokenModel;

import com.linux.plugin.lang.engine.highlight.decision.TieBasicDecision;
import com.linux.plugin.lang.engine.highlight.decision.TieIdentifierDecision;

/** 结绳语法高亮分析器:先切词,再按决策规则着色。 */
public class TieHighlightAnalyzer extends DecisionBaseAnalyzer<TieState> {

    public TieHighlightAnalyzer() {
        addDecision(new TieIdentifierDecision());
        addDecision(new TieBasicDecision());
    }

    @Override
    public TokenModel getTokenModel(CharSequence content) {
        return new TieTokenizer(content).tokenize();
    }

    @Override
    public TieState getDecisionEnv() {
        return new TieState();
    }
}
