package com.linux.plugin.lang.engine.highlight;

/** 结绳词法记号类型。 */
public interface TieToken {
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
}
