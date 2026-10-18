package com.scala.android.lang.highlight;

import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import com.scala.android.lang.ScalaHighlightState;
import com.scala.android.lang.ScalaTokens;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * 基础记号着色：关键字、运算符、字符串、数字、注释、注解、字符字面量。
 */
public class ScalaBasicDecision extends AbstractDecision<ScalaHighlightState> {

    @Override
    public Iterable<DecisionResult> decide(TokenModel model, ScalaHighlightState state) {
        switch (model.token) {
            case ScalaTokens.KEYWORD:
            case ScalaTokens.SOFT_KEYWORD:
                return acceptBold(EditorColorScheme.KEYWORD);
            case ScalaTokens.OPERATOR:
                return accept(EditorColorScheme.OPERATOR);
            case ScalaTokens.STRING:
                return accept(EditorColorScheme.STRING);
            case ScalaTokens.CHAR_LITERAL:
                return accept(EditorColorScheme.LITERAL);
            case ScalaTokens.NUMBER:
                return accept(EditorColorScheme.LITERAL);
            case ScalaTokens.COMMENT:
                return acceptItalic(EditorColorScheme.COMMENT);
            case ScalaTokens.ANNOTATION:
                return accept(EditorColorScheme.ANNOTATION);
            default:
                return refuse();
        }
    }
}
