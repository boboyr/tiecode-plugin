package com.linux.plugin.lang.engine.highlight.decision;

import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;

import com.linux.plugin.lang.engine.highlight.AbstractDecision;
import com.linux.plugin.lang.engine.highlight.TieState;
import com.linux.plugin.lang.engine.highlight.TieToken;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/** 结绳标识符高亮:按前后文区分类名、方法名、属性名和成员字段。 */
public class TieIdentifierDecision extends AbstractDecision<TieState> {

    @Override
    public Iterable<DecisionResult> decide(TokenModel model, TieState state) {
        if (model.token != TieToken.IDENTIFIER) {
            return refuse();
        }

        TokenModel next = nextNonEmpty(model);
        if (next != null && "(".equals(next.text)) {
            return accept(EditorColorScheme.FUNCTION);
        }

        TokenModel prev = prevNonEmpty(model);
        if (prev != null) {
            String text = prev.text;
            if ("类".equals(text) || "继承".equals(text)) {
                return accept(EditorColorScheme.CLASS);
            }
            if ("方法".equals(text) || "事件".equals(text) || "定义事件".equals(text)) {
                return accept(EditorColorScheme.FUNCTION);
            }
            if ("属性读".equals(text) || "属性写".equals(text) || "属性".equals(text)) {
                return accept(EditorColorScheme.FUNCTION_NAME);
            }
            if ("变量".equals(text) || "常量".equals(text) || "为".equals(text)) {
                return accept(EditorColorScheme.VARIABLE);
            }
            if (".".equals(text)) {
                return accept(EditorColorScheme.FIELD);
            }
        }
        return refuse();
    }

    private TokenModel nextNonEmpty(TokenModel model) {
        TokenModel cursor = model.next;
        while (cursor != null) {
            if (cursor.token != TieToken.WHITE_SPACE) {
                return cursor;
            }
            cursor = cursor.next;
        }
        return null;
    }

    private TokenModel prevNonEmpty(TokenModel model) {
        TokenModel cursor = model.prev;
        while (cursor != null) {
            if (cursor.token != TieToken.WHITE_SPACE) {
                return cursor;
            }
            cursor = cursor.prev;
        }
        return null;
    }
}
