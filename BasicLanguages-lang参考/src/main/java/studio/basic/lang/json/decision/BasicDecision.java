package studio.basic.lang.json.decision;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.CHARACTER_LITERAL;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.COLON;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.COMMA;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.FLOATING_POINT_LITERAL;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.INTEGER_LITERAL;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.LBRACE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.LBRACK;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.RBRACE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.RBRACK;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.STRING;

import com.rapid.api.component.widget.editor.HighlightToken;
import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;

import studio.basic.lang.json.JSONState;

public class BasicDecision extends AbstractDecision {
    @Override
    public Iterable<DecisionResult> decide(TokenModel model, JSONState env) {
        switch (model.token) {
            case LBRACK:
            case LBRACE:
            case RBRACK:
            case RBRACE:
            case COLON:
            case COMMA: {
                return singleColor(HighlightToken.OPERATOR);
            }
            case INTEGER_LITERAL:
            case FLOATING_POINT_LITERAL: {
                return singleColor(HighlightToken.LITERAL);
            }
            case CHARACTER_LITERAL:
            case STRING: {
                return singleColor(HighlightToken.STRING);
            }
        }
        return refuse();
    }
}