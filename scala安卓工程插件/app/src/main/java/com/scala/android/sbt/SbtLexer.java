package com.scala.android.sbt;

import java.util.ArrayList;
import java.util.List;

/**
 * sbt 构建脚本的词法分析器（Scala 语言的一个可用子集）。
 *
 * <h3>为什么能自己写</h3>
 * build.sbt 用的是 Scala 语法，但真实工程里出现的形态非常集中：
 * 赋值（{@code := += ++=}）、调用链（{@code .settings(...) .enablePlugins(...)}）、
 * 中缀运算符（{@code % %% / at}）、字面量与少量 {@code val} 定义。
 * 因此这里实现"完整的词法 + 够用的换行推断"，语法层用递归下降
 * （见 {@link SbtParser}）即可覆盖绝大多数 .sbt 文件，且遇到不认识的写法
 * 只记一条警告而不会崩掉。
 *
 * <h3>换行推断</h3>
 * Scala 的换行在多数位置等价于分号。本词法器按 Scala 规范做裁剪版推断：
 * <ul>
 *   <li>{@code ( ) [ ]} 内部不产生换行；</li>
 *   <li>上一有效记号是运算符 {@code . , ( [} 时不产生换行（续行）；</li>
 *   <li>下一有效字符是 {@code .} 时不产生换行（链式调用折行）。</li>
 * </ul>
 */
public final class SbtLexer {

    private static final String OPERATOR_CHARS = "~!@#%^&*-+=|\\:<>?/";

    private final String source;
    private final String fileName;
    private final List<SbtToken> tokens = new ArrayList<>();
    private final List<SbtIssue> issues = new ArrayList<>();

    private int index;
    private int line = 1;
    private int column = 1;
    /** 圆括号/方括号深度：内部换行直接吞掉。 */
    private int softDepth;
    private SbtToken lastSignificant;

    public SbtLexer(String fileName, String source) {
        this.fileName = fileName == null ? "<sbt>" : fileName;
        this.source = source == null ? "" : source;
    }

    public static List<SbtToken> tokenize(String fileName, String source) {
        SbtLexer lexer = new SbtLexer(fileName, source);
        lexer.run();
        return lexer.tokens;
    }

    public List<SbtIssue> getIssues() {
        return issues;
    }

    public List<SbtToken> getTokens() {
        return tokens;
    }

    public void run() {
        while (!atEnd()) {
            char c = peek();
            if (c == '\r') {
                advance();
                continue;
            }
            if (c == '\n') {
                advance();
                emitNewlineIfNeeded();
                continue;
            }
            if (isSpace(c)) {
                advance();
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                skipLineComment();
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                skipBlockComment();
                continue;
            }
            if (c == '"') {
                readString(false);
                continue;
            }
            if (c == '\'') {
                readChar();
                continue;
            }
            if (Character.isDigit(c)) {
                readNumber();
                continue;
            }
            if (isIdentifierStart(c)) {
                readIdentifier();
                continue;
            }
            if (c == '`') {
                readBackquotedIdentifier();
                continue;
            }
            // 字符串插值前缀 s" / f" / raw"
            if (isStringPrefix()) {
                readInterpolatedString();
                continue;
            }
            if (isPunct(c)) {
                add(SbtToken.Kind.PUNCT, String.valueOf(c));
                if (c == '(' || c == '[') {
                    softDepth++;
                } else if (c == ')' || c == ']') {
                    if (softDepth > 0) {
                        softDepth--;
                    }
                }
                advance();
                continue;
            }
            if (OPERATOR_CHARS.indexOf(c) >= 0) {
                readSymbol();
                continue;
            }
            // 真不认识的字符：记一条警告后跳过，保证解析器还能继续跑。
            issues.add(SbtIssue.warning(fileName, line, column,
                    "无法识别的字符 '" + c + "'（已跳过）"));
            advance();
        }
        add(SbtToken.Kind.EOF, "<eof>");
    }

    // ------------------------------------------------------------ 记号产出

    private void add(SbtToken.Kind kind, String text) {
        SbtToken token = new SbtToken(kind, text, line, column);
        if (kind != SbtToken.Kind.NEWLINE) {
            lastSignificant = token;
        }
        tokens.add(token);
    }

    private void add(SbtToken.Kind kind, String text, boolean interpolated, boolean triple) {
        SbtToken token = new SbtToken(kind, text, line, column, interpolated, triple);
        lastSignificant = token;
        tokens.add(token);
    }

    /** 按 Scala 的换行推断决定是否真的产生一个 NEWLINE 记号。 */
    private void emitNewlineIfNeeded() {
        if (softDepth > 0) {
            return;
        }
        if (lastSignificant != null) {
            SbtToken.Kind kind = lastSignificant.kind;
            if (kind == SbtToken.Kind.NEWLINE || kind == SbtToken.Kind.EOF) {
                return;
            }
            if (kind == SbtToken.Kind.SYMBOL) {
                return;
            }
            if (kind == SbtToken.Kind.PUNCT
                    && (lastSignificant.isPunct(".") || lastSignificant.isPunct(",")
                    || lastSignificant.isPunct("(") || lastSignificant.isPunct("["))) {
                return;
            }
            // `in` / `at` / `extends` 这类字母运算符结尾也要续行。
            if (kind == SbtToken.Kind.IDENT && SbtKeywords.isInfixWord(lastSignificant.text)) {
                return;
            }
        }
        // 折行链式调用：下一有效字符是 '.'
        int ahead = index;
        while (ahead < source.length() && isSpace(source.charAt(ahead))) {
            ahead++;
        }
        if (ahead < source.length() && source.charAt(ahead) == '.') {
            return;
        }
        tokens.add(new SbtToken(SbtToken.Kind.NEWLINE, "\n", line, column));
    }

    // ------------------------------------------------------------ 扫描细节

    private void skipLineComment() {
        while (!atEnd() && peek() != '\n') {
            advance();
        }
    }

    private void skipBlockComment() {
        int startLine = line;
        int startColumn = column;
        advance();
        advance();
        int depth = 1;
        while (!atEnd() && depth > 0) {
            char c = peek();
            if (c == '/' && peek(1) == '*') {
                depth++;
                advance();
                advance();
            } else if (c == '*' && peek(1) == '/') {
                depth--;
                advance();
                advance();
            } else {
                advance();
            }
        }
        if (depth > 0) {
            issues.add(SbtIssue.warning(fileName, startLine, startColumn, "块注释没有闭合"));
        }
    }

    private boolean isStringPrefix() {
        char c = peek();
        if (c != 's' && c != 'f' && c != 'r') {
            return false;
        }
        return peek(1) == '"';
    }

    private void readInterpolatedString() {
        advance(); // 前缀字符
        readString(true);
    }

    private void readString(boolean interpolated) {
        int startLine = line;
        int startColumn = column;
        boolean triple = peek(1) == '"' && peek(2) == '"';
        StringBuilder builder = new StringBuilder();
        if (triple) {
            advance();
            advance();
            advance();
            while (!atEnd()) {
                if (peek() == '"' && peek(1) == '"' && peek(2) == '"') {
                    advance();
                    advance();
                    advance();
                    add(SbtToken.Kind.STRING, builder.toString(), interpolated, true);
                    return;
                }
                builder.append(peek());
                advance();
            }
        } else {
            advance();
            while (!atEnd() && peek() != '"') {
                char c = peek();
                if (c == '\\') {
                    advance();
                    builder.append(readEscape());
                    continue;
                }
                if (c == '\n') {
                    break;
                }
                builder.append(c);
                advance();
            }
            if (!atEnd() && peek() == '"') {
                advance();
                add(SbtToken.Kind.STRING, builder.toString(), interpolated, false);
                return;
            }
        }
        issues.add(SbtIssue.error(fileName, startLine, startColumn, "字符串字面量没有闭合"));
        add(SbtToken.Kind.STRING, builder.toString(), interpolated, triple);
    }

    private char readEscape() {
        if (atEnd()) {
            return '\\';
        }
        char c = peek();
        advance();
        switch (c) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            case 'b': return '\b';
            case 'f': return '\f';
            case '0': return '\0';
            case '\\': return '\\';
            case '\'': return '\'';
            case '"': return '"';
            case 'u': {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 4 && !atEnd() && isHex(peek()); i++) {
                    hex.append(peek());
                    advance();
                }
                if (hex.length() == 4) {
                    return (char) Integer.parseInt(hex.toString(), 16);
                }
                return 'u';
            }
            default:
                return c;
        }
    }

    private void readChar() {
        int startLine = line;
        int startColumn = column;
        advance();
        StringBuilder builder = new StringBuilder();
        if (!atEnd() && peek() == '\\') {
            advance();
            builder.append(readEscape());
        } else if (!atEnd() && peek() != '\'') {
            builder.append(peek());
            advance();
        }
        if (!atEnd() && peek() == '\'') {
            advance();
            add(SbtToken.Kind.CHAR, builder.toString());
            return;
        }
        issues.add(SbtIssue.warning(fileName, startLine, startColumn, "字符字面量可疑"));
        add(SbtToken.Kind.CHAR, builder.toString());
    }

    private void readNumber() {
        StringBuilder builder = new StringBuilder();
        while (!atEnd() && (Character.isDigit(peek()) || peek() == '_')) {
            builder.append(peek());
            advance();
        }
        if (!atEnd() && peek() == '.' && Character.isDigit(peek(1))) {
            builder.append('.');
            advance();
            while (!atEnd() && Character.isDigit(peek())) {
                builder.append(peek());
                advance();
            }
        }
        if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
            builder.append(peek());
            advance();
            if (!atEnd() && (peek() == '+' || peek() == '-')) {
                builder.append(peek());
                advance();
            }
            while (!atEnd() && Character.isDigit(peek())) {
                builder.append(peek());
                advance();
            }
        }
        if (!atEnd() && "LlFfDd".indexOf(peek()) >= 0) {
            advance();
        }
        add(SbtToken.Kind.NUMBER, builder.toString());
    }

    private void readIdentifier() {
        StringBuilder builder = new StringBuilder();
        while (!atEnd() && isIdentifierPart(peek())) {
            builder.append(peek());
            advance();
        }
        add(SbtToken.Kind.IDENT, builder.toString());
    }

    private void readBackquotedIdentifier() {
        StringBuilder builder = new StringBuilder();
        advance();
        while (!atEnd() && peek() != '`' && peek() != '\n') {
            builder.append(peek());
            advance();
        }
        if (!atEnd() && peek() == '`') {
            advance();
        }
        add(SbtToken.Kind.IDENT, builder.toString());
    }

    private void readSymbol() {
        StringBuilder builder = new StringBuilder();
        while (!atEnd() && OPERATOR_CHARS.indexOf(peek()) >= 0) {
            char c = peek();
            // 注释起始不能吃进运算符串里
            if (c == '/' && (peek(1) == '/' || peek(1) == '*')) {
                break;
            }
            builder.append(c);
            advance();
        }
        if (builder.length() == 0) {
            builder.append(peek());
            advance();
        }
        add(SbtToken.Kind.SYMBOL, builder.toString());
    }

    // ------------------------------------------------------------ 字符工具

    private boolean atEnd() {
        return index >= source.length();
    }

    private char peek() {
        return peek(0);
    }

    private char peek(int offset) {
        int target = index + offset;
        return target >= 0 && target < source.length() ? source.charAt(target) : '\0';
    }

    private void advance() {
        if (atEnd()) {
            return;
        }
        char c = source.charAt(index++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == '\u000B';
    }

    private static boolean isPunct(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == ',' || c == '.' || c == ';';
    }

    private static boolean isHex(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
