package com.linux.plugin.lang.cpp.operator;

import com.rapid.api.framework.project.lang.DefaultOperatorProvider;

public class CppOperatorProvider extends DefaultOperatorProvider {
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
