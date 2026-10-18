package com.scala.android.lang;

/**
 * Scala 词法记号类型（对应 {@code TokenModel.token}）。
 */
public interface ScalaTokens {

    int WHITE_SPACE = 0;
    int KEYWORD = 1;
    int OPERATOR = 2;
    int STRING = 3;
    int NUMBER = 4;
    int COMMENT = 5;
    int ANNOTATION = 6;
    int IDENTIFIER = 7;
    int BAD = 8;
    int EOF = 9;

    /** 字符字面量与符号字面量（Scala 3 用 {@code 'a'}）。 */
    int CHAR_LITERAL = 10;

    /** 软关键字（{@code given}/{@code using} 等），按关键字着色。 */
    int SOFT_KEYWORD = 11;
}
