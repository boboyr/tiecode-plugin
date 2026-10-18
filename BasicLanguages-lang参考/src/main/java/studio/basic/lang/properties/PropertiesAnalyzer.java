package studio.basic.lang.properties;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.EOF;

import com.rapid.api.lang.engine.highlight.token.IdentifierTokenizer;
import com.rapid.api.lang.engine.highlight.token.TokenModel;
import com.rapid.android.ui.widget.editor.highlight.DecisionBaseAnalyzer;

import studio.basic.lang.properties.decision.BasicDecision;

import java.io.IOException;

public class PropertiesAnalyzer extends DecisionBaseAnalyzer<PropertiesState> {

    public PropertiesAnalyzer() {
        addDecision(new BasicDecision());
    }

    @Override
    public TokenModel getTokenModel(CharSequence content) {
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
    public PropertiesState getDecisionEnv() {
        return new PropertiesState();
    }
}
