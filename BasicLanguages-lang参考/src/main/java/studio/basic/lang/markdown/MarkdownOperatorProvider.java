package studio.basic.lang.markdown;

import com.rapid.api.framework.project.lang.DefaultOperatorProvider;

public class MarkdownOperatorProvider extends DefaultOperatorProvider {
    public final static char[] MARKDOWN_OPERATORS = {
            '#', '<', '>', '/', '!', '%', ':', '{', '}',
            '@', ',', '+', '-', '(', ')', '.', '=', ';',
            '"', '[', ']', '*', '\'', '|', '&', '?'
    };

    @Override
    public char[] getOperators() {
        return MARKDOWN_OPERATORS;
    }
}
