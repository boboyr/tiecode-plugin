package com.goproject.plugin.lang.operator;

import com.rapid.api.framework.project.lang.DefaultOperatorProvider;

public class GoOperatorProvider extends DefaultOperatorProvider {
    public final static char[] HTML_OPERATORS = {
            '<', '>', '/', '=', '"',
            '\'', ':', '(', ')', '{', '}', '[', ']',
            '+', '-', '/', '*', '@',
            '!', '|', '&', '?', '%', '^'
    };

    @Override
    public char[] getOperators() {
        return HTML_OPERATORS;
    }
}
