package com.scala.android.lang.highlight;

import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import com.scala.android.lang.ScalaHighlightState;
import com.scala.android.lang.ScalaTokens;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * 标识符着色：靠前后文区分类型名、方法名、字段名与普通变量。
 *
 * <ul>
 *   <li>{@code name(} → 方法</li>
 *   <li>{@code x.name} → 成员字段</li>
 *   <li>{@code class/trait/object/enum/type/new/extends/with} 后面的名字 → 类型</li>
 *   <li>{@code def} 后面的名字 → 方法名，{@code val/var/given} 后面的名字 → 变量</li>
 *   <li>首字母大写（Scala 的命名约定）→ 类型</li>
 *   <li>其余不染色，交给编辑器默认标识符颜色</li>
 * </ul>
 */
public class ScalaIdentifierDecision extends AbstractDecision<ScalaHighlightState> {

    @Override
    public Iterable<DecisionResult> decide(TokenModel model, ScalaHighlightState state) {
        if (model.token != ScalaTokens.IDENTIFIER) {
            return refuse();
        }
        String text = model.text;
        if (text == null || text.isEmpty()) {
            return refuse();
        }

        TokenModel next = nextNonEmpty(model);
        if (next != null && "(".equals(next.text)) {
            return accept(EditorColorScheme.FUNCTION);
        }
        if (next != null && "[".equals(next.text) && isUpperStart(text)) {
            return accept(EditorColorScheme.CLASS);
        }

        TokenModel prev = prevNonEmpty(model);
        if (prev != null) {
            String previous = prev.text;
            if (".".equals(previous)) {
                return accept(EditorColorScheme.FIELD);
            }
            boolean afterTypeKeyword = "class".equals(previous) || "trait".equals(previous)
                    || "object".equals(previous) || "enum".equals(previous)
                    || "type".equals(previous) || "new".equals(previous)
                    || "extends".equals(previous) || "with".equals(previous);
            // `given Foo` 是给类型实现，`given intOrd` 是给值命名——用大小写区分。
            if (afterTypeKeyword || ("given".equals(previous) && isUpperStart(text))) {
                return accept(EditorColorScheme.CLASS);
            }
            if ("def".equals(previous)) {
                return accept(EditorColorScheme.FUNCTION_NAME);
            }
            if ("val".equals(previous) || "var".equals(previous) || "given".equals(previous)
                    || "using".equals(previous)) {
                return accept(EditorColorScheme.VARIABLE);
            }
        }

        if (isUpperStart(text)) {
            return accept(EditorColorScheme.CLASS);
        }
        return refuse();
    }

    private static boolean isUpperStart(String text) {
        return Character.isUpperCase(text.charAt(0));
    }

    private static TokenModel nextNonEmpty(TokenModel model) {
        TokenModel cursor = model.next;
        while (cursor != null) {
            if (cursor.token != ScalaTokens.WHITE_SPACE) {
                return cursor;
            }
            cursor = cursor.next;
        }
        return null;
    }

    private static TokenModel prevNonEmpty(TokenModel model) {
        TokenModel cursor = model.prev;
        while (cursor != null) {
            if (cursor.token != ScalaTokens.WHITE_SPACE) {
                return cursor;
            }
            cursor = cursor.prev;
        }
        return null;
    }
}
