package studio.basic.lang.json.decision;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.LBRACE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.LBRACK;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.RBRACE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.RBRACK;

import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import studio.basic.lang.json.JSONState;

public class CodeBlockDecision extends AbstractDecision {
    @Override
    public Iterable<DecisionResult> decide(TokenModel model, JSONState env) {
        switch (model.token) {
            case LBRACK:
            case LBRACE: {
                DecisionResult result = new DecisionResult(DecisionResult.ResultKind.BLOCK_HEAD, null);
                return single(result);
            }
            case RBRACK:
            case RBRACE: {
                DecisionResult result = new DecisionResult(DecisionResult.ResultKind.BLOCK_TAIL, null);
                return single(result);
            }
        }
        DecisionResult result = new DecisionResult();
        result.kind = DecisionResult.ResultKind.REFUSE;
        return single(result);
    }
}