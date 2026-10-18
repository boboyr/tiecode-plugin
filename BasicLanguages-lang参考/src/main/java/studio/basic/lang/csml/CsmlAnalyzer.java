package studio.basic.lang.csml;

import com.rapid.android.ui.widget.editor.highlight.DecisionBaseAnalyzer;
import com.rapid.api.lang.engine.highlight.token.TokenModel;

import studio.basic.lang.csml.decision.BasicDecision;
import studio.basic.lang.csml.decision.CsmlEnv;

public class CsmlAnalyzer extends DecisionBaseAnalyzer<CsmlEnv> {

    public CsmlAnalyzer() {
        addDecision(new BasicDecision());
    }

    @Override
    public TokenModel getTokenModel(CharSequence content) {
        return getIdentifierTokenModel(content);
    }

    @Override
    public CsmlEnv getDecisionEnv() {
        return new CsmlEnv();
    }
}
