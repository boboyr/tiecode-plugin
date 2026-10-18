package com.scala.android.lang;

import com.rapid.api.lang.engine.highlight.token.TokenModel;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Scala 词法扫描器：把源码切成 {@link TokenModel} 链，供
 * {@code DecisionBaseAnalyzer} 做高亮决策。
 *
 * <p>支持：空白、行注释、可嵌套块注释、普通/多行({@code """})字符串、
 * 字符字面量、{@code @注解}、数字（含 {@code 0x}/{@code _}/后缀）、
 * 关键字、反引号标识符、运算符。
 */
public class ScalaTokenizer {

    /** Scala 3 硬关键字。 */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export",
            "extends", "false", "final", "finally", "for", "if", "implicit", "import",
            "lazy", "match", "new", "null", "object", "override", "package", "private",
            "protected", "return", "sealed", "super", "this", "throw", "trait", "true",
            "try", "type", "val", "var", "while", "with", "yield"
    ));

    /**
     * Scala 3 软关键字：只在特定位置有意义，但着色成关键字更符合直觉。
     * （{@code inline}/{@code opaque} 等；{@code as}/{@code derives} 不在此列，
     * 它们在 Scala 3 里是普通标识符。）
     */
    private static final Set<String> SOFT_KEYWORDS = new HashSet<>(Arrays.asList(
            "given", "using", "then", "extension", "end", "infix", "transparent",
            "open", "inline", "opaque", "throws", "derives", "macro"
    ));

    private final String text;
    private int index;
    private int line;
    private int column;

    public ScalaTokenizer(CharSequence content) {
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
                kind = ScalaTokens.WHITE_SPACE;
            } else if (c == '/' && index + 1 < length && text.charAt(index + 1) == '/') {
                while (index < length && text.charAt(index) != '\n') {
                    advance(text.charAt(index));
                }
                kind = ScalaTokens.COMMENT;
            } else if (c == '/' && index + 1 < length && text.charAt(index + 1) == '*') {
                readBlockComment();
                kind = ScalaTokens.COMMENT;
            } else if (c == '"' && index + 2 < length
                    && text.charAt(index + 1) == '"' && text.charAt(index + 2) == '"') {
                readTripleQuotedString();
                kind = ScalaTokens.STRING;
            } else if (c == '"') {
                readQuotedString('"');
                kind = ScalaTokens.STRING;
            } else if (c == '\'') {
                readCharLiteral();
                kind = ScalaTokens.CHAR_LITERAL;
            } else if (c == '`') {
                advance(c);
                while (index < length && text.charAt(index) != '`' && text.charAt(index) != '\n') {
                    advance(text.charAt(index));
                }
                if (index < length && text.charAt(index) == '`') {
                    advance('`');
                }
                kind = ScalaTokens.IDENTIFIER;
            } else if (c == '@') {
                advance('@');
                while (index < length && isIdentifierPart(text.charAt(index))) {
                    advance(text.charAt(index));
                }
                kind = ScalaTokens.ANNOTATION;
            } else if (Character.isDigit(c)) {
                readNumber();
                kind = ScalaTokens.NUMBER;
            } else if (isIdentifierStart(c)) {
                while (index < length && isIdentifierPart(text.charAt(index))) {
                    advance(text.charAt(index));
                }
                String word = text.substring(start, index);
                if (KEYWORDS.contains(word)) {
                    kind = ScalaTokens.KEYWORD;
                } else if (SOFT_KEYWORDS.contains(word)) {
                    kind = ScalaTokens.SOFT_KEYWORD;
                } else {
                    kind = ScalaTokens.IDENTIFIER;
                }
            } else {
                advance(c);
                kind = ScalaTokens.OPERATOR;
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

    private void readBlockComment() {
        advance('/');
        advance('*');
        int depth = 1;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                advance('/');
                advance('*');
                depth++;
            } else if (c == '*' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
                advance('*');
                advance('/');
                depth--;
                if (depth == 0) {
                    return;
                }
            } else {
                advance(c);
            }
        }
    }

    private void readTripleQuotedString() {
        advance('"');
        advance('"');
        advance('"');
        while (index < text.length()) {
            if (text.charAt(index) == '"' && index + 2 < text.length()
                    && text.charAt(index + 1) == '"' && text.charAt(index + 2) == '"') {
                advance('"');
                advance('"');
                advance('"');
                return;
            }
            advance(text.charAt(index));
        }
    }

    private void readQuotedString(char quote) {
        advance(quote);
        while (index < text.length()) {
            char c = text.charAt(index);
            advance(c);
            if (c == '\\' && index < text.length()) {
                advance(text.charAt(index));
                continue;
            }
            if (c == quote) {
                return;
            }
        }
    }

    /** 字符字面量：{@code 'a'} / {@code '\n'}；单个 {@code '} 不构成字面量时按运算符处理。 */
    private void readCharLiteral() {
        if (index + 2 < text.length()) {
            char next = text.charAt(index + 1);
            if (next == '\\') {
                if (index + 3 < text.length() && text.charAt(index + 3) == '\'') {
                    advance('\'');
                    advance('\\');
                    advance(text.charAt(index));
                    advance('\'');
                    return;
                }
            } else if (text.charAt(index + 2) == '\'') {
                advance('\'');
                advance(next);
                advance('\'');
                return;
            }
        }
        // 不是字符字面量（例如 Scala 3 的引用语法），当成普通符号。
        advance('\'');
    }

    private void readNumber() {
        if (text.charAt(index) == '0' && index + 1 < text.length()
                && (text.charAt(index + 1) == 'x' || text.charAt(index + 1) == 'X')) {
            advance(text.charAt(index));
            advance(text.charAt(index));
            while (index < text.length() && isHexPart(text.charAt(index))) {
                advance(text.charAt(index));
            }
        } else {
            while (index < text.length() && isDecimalPart(text.charAt(index))) {
                advance(text.charAt(index));
            }
            // 指数部分：1.5e-3 / 2E+10
            if (index < text.length() && isExponentMark(text.charAt(index))) {
                advance(text.charAt(index));
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    advance(text.charAt(index));
                }
                while (index < text.length() && isDecimalPart(text.charAt(index))) {
                    advance(text.charAt(index));
                }
            }
        }
        // 类型后缀 L / F / D
        if (index < text.length()) {
            char suffix = Character.toUpperCase(text.charAt(index));
            if (suffix == 'L' || suffix == 'F' || suffix == 'D') {
                advance(text.charAt(index));
            }
        }
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
        return Character.isLetter(c) || c == '_' || c == '$' || c >= '\u0080';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || Character.isDigit(c);
    }

    private static boolean isHexPart(char c) {
        char lower = Character.toLowerCase(c);
        return Character.isDigit(c) || (lower >= 'a' && lower <= 'f') || c == '_';
    }

    /** 十进制数字部分（不含指数符号）。 */
    private static boolean isDecimalPart(char c) {
        return Character.isDigit(c) || c == '_' || c == '.';
    }

    private static boolean isExponentMark(char c) {
        return c == 'e' || c == 'E';
    }
}
