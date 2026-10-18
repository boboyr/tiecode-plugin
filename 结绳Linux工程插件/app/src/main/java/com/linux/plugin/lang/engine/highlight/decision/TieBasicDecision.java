package com.linux.plugin.lang.engine.highlight.decision;

import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;

import com.linux.plugin.lang.engine.highlight.AbstractDecision;
import com.linux.plugin.lang.engine.highlight.TieState;
import com.linux.plugin.lang.engine.highlight.TieToken;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/** 结绳基础记号高亮:关键字、运算符、字符串、数字、注释、注解。 */
public class TieBasicDecision extends AbstractDecision<TieState> {

    @Override
    public Iterable<DecisionResult> decide(TokenModel model, TieState state) {
        switch (model.token) {
            case TieToken.KEYWORD:
                return acceptBold(EditorColorScheme.KEYWORD);
            case TieToken.OPERATOR:
                return accept(EditorColorScheme.OPERATOR);
            case TieToken.STRING:
                return accept(EditorColorScheme.STRING);
            case TieToken.NUMBER:
                return accept(EditorColorScheme.LITERAL);
            case TieToken.COMMENT:
                return acceptItalic(EditorColorScheme.COMMENT);
            case TieToken.ANNOTATION:
                return accept(EditorColorScheme.ANNOTATION);
            default:
                return refuse();
        }
    }
}
