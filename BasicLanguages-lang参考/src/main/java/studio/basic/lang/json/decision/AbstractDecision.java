package studio.basic.lang.json.decision;

import static com.rapid.api.lang.engine.highlight.token.Token.WHITESPACE;

import com.rapid.api.lang.engine.highlight.decision.Decision;
import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import studio.basic.lang.json.JSONState;

import java.util.Arrays;
import java.util.Collections;

public abstract class AbstractDecision implements Decision<JSONState> {

    public Iterable<DecisionResult> single(DecisionResult result) {
        return Collections.singleton(result);
    }

    public Iterable<DecisionResult> singleColor(int schema) {
        DecisionResult result = new DecisionResult();
        result.kind = DecisionResult.ResultKind.ACCEPT;
        result.content = new DecisionResult.StyleContent(schema);
        return Collections.singleton(result);
    }

    public Iterable<DecisionResult> results(DecisionResult... result) {
        return Arrays.asList(result);
    }

    public Iterable<DecisionResult> refuse() {
        DecisionResult result = new DecisionResult();
        result.kind = DecisionResult.ResultKind.REFUSE;
        return single(result);
    }

    public TokenModel getNextNonEmptyToken(TokenModel model) {
        model = model.next;
        while (model != null) {
            if (model.token != WHITESPACE) {
                return model;
            }
            model = model.next;
        }
        return null;
    }

    public TokenModel getPrevNonEmptyToken(TokenModel model) {
        model = model.prev;
        while (model != null) {
            if (model.token != WHITESPACE) {
                return model;
            }
            model = model.prev;
        }
        return null;
    }
}
