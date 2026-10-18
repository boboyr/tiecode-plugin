package studio.basic.lang.gitignore.decision;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.DIV;

import android.graphics.drawable.Drawable;

import com.rapid.api.framework.common.CommonServiceKeys;
import com.rapid.api.framework.common.icon.IconService;
import com.rapid.api.graphics.icon.Icon;
import com.rapid.api.lang.engine.highlight.decision.AbstractDecision;
import com.rapid.api.lang.engine.highlight.decision.DecisionResult;
import com.rapid.api.lang.engine.highlight.token.IdentifierToken;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import com.rapid.framework.Rapid;

public class BasicDecision extends AbstractDecision<Void> {
    @Override
    public Iterable<DecisionResult> decide(TokenModel model, Void env) {
        if (model.token == DIV) {
            if (model.prev == null || model.prev.token == IdentifierToken.NEWLINE) {
                IconService iconProvider = Rapid.getInstance().getService(CommonServiceKeys.ICON);
                Icon icon = iconProvider.getIcon(IconService.DIRECTORY);
                Drawable drawable = icon.newDrawable();
                DecisionResult result = new DecisionResult(
                        DecisionResult.ResultKind.ICON,
                        new DecisionResult.IconContent(drawable)
                );
                return single(result);
            } else {
                return refuse();
            }
        }
        return refuse();
    }
}