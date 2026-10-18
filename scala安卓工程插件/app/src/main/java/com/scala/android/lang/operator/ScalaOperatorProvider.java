package com.scala.android.lang.operator;

import com.rapid.api.framework.project.lang.DefaultOperatorProvider;

/**
 * Scala 的运算符字符（影响换行/缩进等编辑行为）。
 */
public class ScalaOperatorProvider extends DefaultOperatorProvider {

    private static final char[] SCALA_OPERATORS = {
            '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?', ':',
            '.', ',', ';', '(', ')', '{', '}', '[', ']', '@', '#', '\\', '\'', '"', '`', '$'
    };

    @Override
    public char[] getOperators() {
        return SCALA_OPERATORS;
    }
}
