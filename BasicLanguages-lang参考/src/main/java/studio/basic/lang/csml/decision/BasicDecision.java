package studio.basic.lang.csml.decision;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.AT;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.IDENTIFIER;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.NEWLINE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.SHARP;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.WHITESPACE;

import android.util.Pair;
import android.util.SparseIntArray;

import com.rapid.api.component.widget.editor.HighlightToken;
import com.rapid.api.lang.engine.highlight.decision.AbstractDecision;
import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import com.rapid.framework.data.document.Position;

public class BasicDecision extends AbstractDecision<CsmlEnv> {
    @Override
    public Iterable<DecisionResult> decide(TokenModel model, CsmlEnv env) {
        if (model.token == AT && getPrevTokenSkipSpace(model) == null) {
            return singleColor(HighlightToken.OPERATOR);
        } else if (model.token == SHARP) {
            if (env.marked) {
                env.marked = false;
            } else {
                env.marked = true;
            }
            return singleColor(HighlightToken.CLASS);
        } else if (model.token == IDENTIFIER) {
            if (model.prev != null) {
                if (model.prev.token == AT) {
                    return singleColor(HighlightToken.KEYWORD);
                } else if (model.prev.token == SHARP
                        && model.next != null
                        && model.next.token == SHARP
                        && env.marked) {
                    env.key = model.text;
                    increaseExtraColumns(model.line, env.extraLineColumns, model.text.length() + 2);
                    return singleColor(HighlightToken.CLASS);
                } else {
                    Position position = new Position(model.line,
                            model.column - getExtraColumns(model.line, env.extraLineColumns));
                    env.highlights.add(new Pair<>(env.key, position));
                }
            }
        } else if (model.token != WHITESPACE && model.token != NEWLINE) {
            Position position = new Position(model.line,
                    model.column - getExtraColumns(model.line, env.extraLineColumns));
            env.highlights.add(new Pair<>(env.key, position));
        }
        return refuse();
    }

    private void increaseExtraColumns(int line, SparseIntArray array, int columns) {
        int result = array.get(line);
        array.put(line, result + columns);
    }

    private int getExtraColumns(int line, SparseIntArray array) {
        int result = array.get(line);
        return result;
    }
}