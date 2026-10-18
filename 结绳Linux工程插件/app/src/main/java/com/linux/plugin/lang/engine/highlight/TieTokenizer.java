package com.linux.plugin.lang.engine.highlight;

import com.rapid.api.lang.engine.highlight.token.TokenModel;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 结绳词法扫描器:把源码切分为 {@link TokenModel} 链表,供
 * {@code DecisionBaseAnalyzer} 做语法高亮决策。
 *
 * <p>当前支持:空白、行注释、块注释、普通字符串、长文本([[...]])、字符字面量、
 * 注解标记、数字、关键字、标识符和运算符。
 */
public class TieTokenizer {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "包名", "类", "继承", "变量", "常量", "为", "本对象", "父对象", "属于", "创建",
            "返回", "结束", "属性", "事件", "定义事件", "订阅事件", "方法", "属性读", "属性写",
            "假如", "是", "如果", "则", "否则", "且", "或", "循环", "退出循环", "跳过循环",
            "等待", "真", "假", "空"));

    private final String text;
    private int index;
    private int line;
    private int column;

    public TieTokenizer(CharSequence content) {
        this.text = content == null ? "" : content.toString();
    }

    public TokenModel tokenize() {
        TokenModel head = null;
        TokenModel tail = null;
        int length = text.length();
        while (index < length) {
            int startLine = line;
            int startColumn = column;
            int start = index;
            int kind;
            char c = text.charAt(index);

            if (isWhitespace(c)) {
                while (index < length && isWhitespace(text.charAt(index))) {
                    advance(text.charAt(index));
                }
                kind = TieToken.WHITE_SPACE;
            } else if (c == '/' && index + 1 < length && text.charAt(index + 1) == '/') {
                while (index < length && text.charAt(index) != '\n') {
                    advance(text.charAt(index));
                }
                kind = TieToken.COMMENT;
            } else if (c == '/' && index + 1 < length && text.charAt(index + 1) == '*') {
                advance('/');
                advance('*');
                while (index < length) {
                    if (text.charAt(index) == '*' && index + 1 < length && text.charAt(index + 1) == '/') {
                        advance('*');
                        advance('/');
                        break;
                    }
                    advance(text.charAt(index));
                }
                kind = TieToken.COMMENT;
            } else if (c == '[' && index + 1 < length && text.charAt(index + 1) == '[') {
                advance('[');
                advance('[');
                while (index < length) {
                    if (text.charAt(index) == ']' && index + 1 < length && text.charAt(index + 1) == ']') {
                        advance(']');
                        advance(']');
                        break;
                    }
                    advance(text.charAt(index));
                }
                kind = TieToken.STRING;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                advance(c);
                while (index < length) {
                    char d = text.charAt(index);
                    advance(d);
                    if (d == '\\' && index < length) {
                        advance(text.charAt(index));
                        continue;
                    }
                    if (d == quote) {
                        break;
                    }
                }
                kind = TieToken.STRING;
            } else if (c == '@') {
                advance('@');
                while (index < length && isIdentifierPart(text.charAt(index))) {
                    advance(text.charAt(index));
                }
                kind = TieToken.ANNOTATION;
            } else if (Character.isDigit(c)) {
                while (index < length && isNumberPart(text.charAt(index))) {
                    advance(text.charAt(index));
                }
                kind = TieToken.NUMBER;
            } else if (isIdentifierStart(c)) {
                while (index < length && isIdentifierPart(text.charAt(index))) {
                    advance(text.charAt(index));
                }
                String word = text.substring(start, index);
                kind = KEYWORDS.contains(word) ? TieToken.KEYWORD : TieToken.IDENTIFIER;
            } else {
                advance(c);
                kind = TieToken.OPERATOR;
            }

            TokenModel node = new TokenModel(kind, startLine, startColumn, text.substring(start, index));
            if (head == null) {
                head = node;
            } else {
                tail.next = node;
                node.prev = tail;
            }
            tail = node;
        }
        return head;
    }

    private void advance(char c) {
        index++;
        if (c == '\n') {
            line++;
            column = 0;
        } else {
            column++;
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= '\u0080';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || Character.isDigit(c);
    }

    private static boolean isNumberPart(char c) {
        if (Character.isDigit(c) || c == '.') {
            return true;
        }
        char lower = Character.toLowerCase(c);
        return (lower >= 'a' && lower <= 'f') || lower == 'x' || lower == 'l' || lower == 'f';
    }
}
