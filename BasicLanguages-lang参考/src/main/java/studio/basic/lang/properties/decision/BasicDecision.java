package studio.basic.lang.properties.decision;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.EQ;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.NEWLINE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.SHARP;

import com.rapid.api.component.widget.editor.HighlightToken;
import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import studio.basic.lang.properties.PropertiesState;

public class BasicDecision extends AbstractDecision {
    public final static int STATE_COMMENT = 0;
    public final static int STATE_KEY = 1;
    public final static int STATE_VALUE = 2;
    private int state = STATE_KEY;

    @Override
    public Iterable<DecisionResult> decide(TokenModel model, PropertiesState env) {
        if (model.token == SHARP) {
            state = STATE_COMMENT;
            return singleColor(HighlightToken.COMMENT);
        }
        if (model.token == EQ && state != STATE_COMMENT) {
            state = STATE_VALUE;
            return singleColor(HighlightToken.OPERATOR);
        } else if (model.token == NEWLINE) {
            if (state == STATE_COMMENT) {
                state = STATE_KEY;
            } else if (model.prev.text.equals("\\")) {
                state = STATE_VALUE;
            } else {
                state = STATE_KEY;
            }
        }
        if (state == STATE_COMMENT) {
            return singleColor(HighlightToken.COMMENT);
        } else if (state == STATE_KEY) {
            return singleColor(HighlightToken.KEYWORD);
        } else {
            return singleColor(HighlightToken.STRING);
        }
    }
}