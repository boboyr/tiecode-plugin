package studio.basic.lang.json;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.EOF;

import androidx.annotation.Nullable;

import com.rapid.api.lang.engine.highlight.token.IdentifierTokenizer;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import com.rapid.android.ui.widget.editor.highlight.DecisionBaseAnalyzer;

import studio.basic.lang.json.decision.BasicDecision;
import studio.basic.lang.json.decision.CodeBlockDecision;

import java.io.IOException;

import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.brackets.BracketsProvider;
import io.github.rosemoe.sora.lang.brackets.OnlineBracketsMatcher;

public class JSONAnalyzer extends DecisionBaseAnalyzer<JSONState> {
    private final static char[] brackets = {'(', ')', '[', ']', '{', '}'};
    private final static int bracketsLimit = 1000;
    private final BracketsProvider bracketsProvider;
    private StyleReceiver receiver;

    public JSONAnalyzer() {
        this.bracketsProvider = new OnlineBracketsMatcher(brackets, bracketsLimit);
        addDecision(new BasicDecision());
        addDecision(new CodeBlockDecision());
    }

    @Override
    public void setReceiver(@Nullable StyleReceiver receiver) {
        super.setReceiver(receiver);
        this.receiver = receiver;
    }

    @Override
    public TokenModel getTokenModel(CharSequence content) {
        receiver.updateBracketProvider(this, bracketsProvider);
        TokenModel head = null;
        TokenModel node = null;
        IdentifierTokenizer tokenizer = new IdentifierTokenizer(content);
        try {
            while (true) {
                int token = tokenizer.yylex();
                if (token == EOF) {
                    break;
                }
                int line = tokenizer.yyline();
                int column = tokenizer.yycolumn();
                int index = tokenizer.yychar();
                String text = tokenizer.yytext();
                if (node == null) {
                    node = new TokenModel(token, line, column, index, text);
                    head = node;
                } else {
                    TokenModel curr = node;
                    node =  new TokenModel(token, line, column, index, text);
                    curr.next = node;
                    node.prev = curr;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return head;
    }

    @Override
    public JSONState getDecisionEnv() {
        return new JSONState();
    }
}
