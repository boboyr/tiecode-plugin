package studio.basic.lang.gitignore;

import com.rapid.android.ui.widget.editor.highlight.DecisionBaseAnalyzer;
import com.rapid.api.lang.engine.highlight.token.TokenModel;

import studio.basic.lang.gitignore.decision.BasicDecision;

public class GitignoreAnalyzer extends DecisionBaseAnalyzer<Void> {

    public GitignoreAnalyzer() {
        addDecision(new BasicDecision());
    }

    @Override
    public TokenModel getTokenModel(CharSequence content) {
        return getIdentifierTokenModel(content);
    }

    @Override
    public Void getDecisionEnv() {
        return null;
    }
}
