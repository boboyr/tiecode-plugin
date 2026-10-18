package com.scala.android.lang.highlight;

import com.rapid.api.lang.engine.highlight.decision.Decision;
import com.rapid.api.lang.engine.highlight.decision.DecisionResult;

import java.util.Collections;

/**
 * 高亮决策基类：提供构造 ACCEPT / REFUSE 结果的便捷方法。
 */
public abstract class AbstractDecision<S> implements Decision<S> {

    protected Iterable<DecisionResult> single(DecisionResult result) {
        return Collections.singleton(result);
    }

    protected Iterable<DecisionResult> accept(int colorSchema) {
        DecisionResult result = new DecisionResult();
        result.kind = DecisionResult.ResultKind.ACCEPT;
        result.content = new DecisionResult.StyleContent(colorSchema);
        return single(result);
    }

    protected Iterable<DecisionResult> acceptBold(int colorSchema) {
        DecisionResult result = new DecisionResult();
        result.kind = DecisionResult.ResultKind.ACCEPT;
        result.content = new DecisionResult.StyleContent(colorSchema, true, false);
        return single(result);
    }

    protected Iterable<DecisionResult> acceptItalic(int colorSchema) {
        DecisionResult result = new DecisionResult();
        result.kind = DecisionResult.ResultKind.ACCEPT;
        result.content = new DecisionResult.StyleContent(colorSchema, false, true);
        return single(result);
    }

    protected Iterable<DecisionResult> refuse() {
        DecisionResult result = new DecisionResult();
        result.kind = DecisionResult.ResultKind.REFUSE;
        return single(result);
    }
}
