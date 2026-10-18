package com.scala.android.sbt;

import java.util.ArrayList;
import java.util.List;

/**
 * sbt 脚本的递归下降语法分析器。
 *
 * <h3>覆盖的语法</h3>
 * <pre>
 * 语句      := 定义 | import/package | 表达式
 * 定义      := ["lazy"] ("val"|"var"|"def") 名字 [参数] "=" 表达式
 * 表达式    := 后缀链 | 中缀链 | 赋值
 * 赋值      := 表达式 赋值运算符 表达式
 * 后缀链    := 基本式 { "." 名字 ["(" 实参串 ")"] | "[" 类型参数 "]" }
 * 中缀链    := 基本式 { 运算符 基本式 }
 * 基本式    := 标识符 | 字符串 | 数字 | 布尔 | "(" 表达式串 ")" | "{" 语句串 "}"
 * </pre>
 *
 * <p>赋值运算符（{@code := += ++=} …）优先级最低，且右侧允许换行续写，
 * 这与 sbt 的 setting 定义一致；{@code ThisBuild / name := v} 会被解析成
 * {@code ASSIGN(INFIX(ThisBuild, /, name), :=, v)}。
 */
public final class SbtParser {

    private final String fileName;
    private final List<SbtToken> tokens;
    private final List<SbtIssue> issues = new ArrayList<>();
    private int pos;

    public SbtParser(String fileName, List<SbtToken> tokens) {
        this.fileName = fileName == null ? "<sbt>" : fileName;
        this.tokens = tokens;
    }

    public static SbtParser of(String fileName, String source) {
        SbtLexer lexer = new SbtLexer(fileName, source);
        lexer.run();
        SbtParser parser = new SbtParser(fileName, lexer.getTokens());
        parser.issues.addAll(lexer.getIssues());
        return parser;
    }

    public List<SbtIssue> getIssues() {
        return issues;
    }

    /** 解析整个文件，返回顶层语句列表。 */
    public List<SbtNode> parseFile() {
        List<SbtNode> statements = statementList(true);
        return statements;
    }

    // ------------------------------------------------------------ 语句层

    private List<SbtNode> statementList(boolean topLevel) {
        List<SbtNode> statements = new ArrayList<>();
        while (true) {
            skipSeparators();
            SbtToken token = peek();
            if (token.is(SbtToken.Kind.EOF)) {
                break;
            }
            if (!topLevel && token.isPunct("}")) {
                break;
            }
            SbtNode statement = statement();
            if (statement != null) {
                statements.add(statement);
            }
            // 兜底：一但某个语句没消费任何记号，强制前进避免死循环。
            if (statement == null) {
                SbtToken current = peek();
                issues.add(SbtIssue.warning(fileName, current.line, current.column,
                        "无法解析的记号 '" + current.text + "'，已跳过"));
                next();
            }
        }
        return statements;
    }

    private SbtNode statement() {
        skipSeparators();
        int start = pos;
        SbtToken token = peek();
        if (token.is(SbtToken.Kind.IDENT)) {
            String text = token.text;
            if ("import".equals(text) || "package".equals(text)) {
                return skippedStatement(text);
            }
            if (SbtKeywords.isDefinitionKeyword(text)) {
                return definition(false);
            }
            if ("lazy".equals(text) && peek(1).isIdent("val")) {
                next();
                return definition(true);
            }
            if (SbtKeywords.isControlKeyword(text)) {
                return skippedStatement(text);
            }
        }
        if (token.isSymbol("@")) {
            // 注解（@transient 等）：跳到行尾
            return skippedStatement("@");
        }
        SbtNode expression = expression();
        if (pos == start) {
            next();
            return null;
        }
        return expression;
    }

    /** val / var / def 定义；lazy val 由 flag 标记。 */
    private SbtNode definition(boolean lazy) {
        SbtToken keyword = next();
        SbtToken nameToken = peek();
        if (!nameToken.is(SbtToken.Kind.IDENT)) {
            return skippedStatement(keyword.text);
        }
        next();
        SbtNode node = new SbtNode(SbtNode.Kind.DEFN, keyword.line, keyword.column);
        node.name = nameToken.text;
        node.flag = lazy;
        if ("def".equals(keyword.text)) {
            node.text = "def";
        }
        // 跳过类型/参数声明，直到 '='
        while (!peek().is(SbtToken.Kind.EOF) && !peek().isSymbol("=")) {
            if (peek().is(SbtToken.Kind.NEWLINE) || peek().isPunct(";")) {
                // 抽象 val（无 '='）：当成无体定义
                return node;
            }
            next();
        }
        if (peek().isSymbol("=")) {
            next();
            node.right = expression();
        }
        return node;
    }

    /** import / 控制流等：整条语句原样吃掉（保留文本供诊断）。 */
    private SbtNode skippedStatement(String keyword) {
        SbtToken first = peek();
        StringBuilder builder = new StringBuilder();
        int depth = 0;
        while (!peek().is(SbtToken.Kind.EOF)) {
            SbtToken token = peek();
            if (depth == 0 && (token.is(SbtToken.Kind.NEWLINE) || token.isPunct(";"))) {
                break;
            }
            if (token.isPunct("{") || token.isPunct("(")) {
                depth++;
            } else if (token.isPunct("}") || token.isPunct(")")) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            builder.append(token.text).append(' ');
            next();
        }
        SbtNode node = new SbtNode(SbtNode.Kind.SKIPPED, first.line, first.column);
        node.name = keyword;
        node.text = builder.toString().trim();
        // import/package 是构建脚本的常态，不算"不认识"，不打扰用户。
        if (!"import".equals(keyword) && !"package".equals(keyword)) {
            issues.add(SbtIssue.warning(fileName, first.line, first.column,
                    "已按原文跳过不参与求值的语句: " + keyword + " ..."));
        }
        return node;
    }

    // ------------------------------------------------------------ 表达式层

    private SbtNode expression() {
        SbtNode left = postfix();
        // 中缀链（左结合）
        while (true) {
            SbtToken token = peek();
            if (token.is(SbtToken.Kind.SYMBOL) || token.is(SbtToken.Kind.IDENT)) {
                String op = token.text;
                if (SbtKeywords.isAssignOperator(op)) {
                    break;
                }
                boolean operatorLike = token.is(SbtToken.Kind.SYMBOL)
                        || SbtKeywords.isInfixWord(op);
                if (!operatorLike) {
                    break;
                }
                if (!canStartExpression(peek(1))) {
                    break;
                }
                next();
                SbtNode right = postfix();
                left = SbtNode.infix(left, op, right, token.line, token.column);
                continue;
            }
            break;
        }
        // 无点方法调用：`模块 exclude("g","a")`、`x intransitive` 等。
        // Scala 里字母起始的方法优先级低于符号运算符，所以它作用在整条中缀链上。
        while (true) {
            SbtToken token = peek();
            if (token.is(SbtToken.Kind.IDENT)
                    && !SbtKeywords.isInfixWord(token.text)
                    && !SbtKeywords.isAssignOperator(token.text)
                    && !SbtKeywords.isStatementKeyword(token.text)
                    && !SbtKeywords.isControlKeyword(token.text)
                    && (peek(1).isPunct("(") || peek(1).isPunct("{"))) {
                next();
                List<SbtNode> args = argumentList();
                left = SbtNode.call(left, token.text, args, token.line, token.column);
                continue;
            }
            break;
        }
        // 赋值
        SbtToken token = peek();
        if ((token.is(SbtToken.Kind.SYMBOL) || token.is(SbtToken.Kind.IDENT))
                && SbtKeywords.isAssignOperator(token.text)) {
            next();
            SbtNode right = assignmentRight();
            left = SbtNode.infix(left, token.text, right, token.line, token.column);
            left.kind = SbtNode.Kind.ASSIGN;
        }
        return left;
    }

    /** 赋值右侧：允许块，也允许换行后继续（sbt 里 `:=` 后面常常直接换行再接表达式）。 */
    private SbtNode assignmentRight() {
        while (peek().is(SbtToken.Kind.NEWLINE)) {
            next();
        }
        if (peek().isPunct("{")) {
            return block();
        }
        return expression();
    }

    private SbtNode postfix() {
        SbtNode node = primary();
        while (true) {
            SbtToken token = peek();
            if (token.isPunct(".")) {
                next();
                SbtToken nameToken = peek();
                if (!(nameToken.is(SbtToken.Kind.IDENT) || nameToken.is(SbtToken.Kind.SYMBOL))) {
                    issues.add(SbtIssue.warning(fileName, nameToken.line, nameToken.column,
                            "'.' 后面缺少成员名"));
                    return node;
                }
                next();
                if (peek().isPunct("(") || peek().isPunct("{")) {
                    List<SbtNode> args = argumentList();
                    node = SbtNode.call(node, nameToken.text, args, nameToken.line, nameToken.column);
                } else {
                    node = SbtNode.select(node, nameToken.text, nameToken.line, nameToken.column);
                }
                continue;
            }
            if (token.isPunct("[")) {
                // 类型参数：跳过整对方括号，再补上可能的实参
                skipBalanced("[", "]");
                if (peek().isPunct("(") || peek().isPunct("{")) {
                    List<SbtNode> args = argumentList();
                    if (node.is(SbtNode.Kind.IDENT)) {
                        node = SbtNode.call(null, node.name, args, node.line, node.column);
                    } else {
                        node.args().addAll(args);
                    }
                }
                continue;
            }
            // 无点方法调用在 expression() 层处理（字母开头的方法优先级最低，
            // 作用在整条中缀链上，例如 `"a" % "b" % "1.0" exclude("g", "a")`）。
            break;
        }
        return node;
    }

    private SbtNode primary() {
        skipSoftNewlines();
        SbtToken token = peek();
        switch (token.kind) {
            case STRING:
                next();
                return SbtNode.string(token.text, token.line, token.column);
            case CHAR:
                next();
                return SbtNode.string(token.text, token.line, token.column);
            case NUMBER: {
                next();
                SbtNode node = new SbtNode(SbtNode.Kind.NUMBER, token.line, token.column);
                node.text = token.text;
                return node;
            }
            case PUNCT: {
                if (token.isPunct("(")) {
                    next();
                    List<SbtNode> elements = expressionList(")");
                    expect(")");
                    if (elements.size() == 1) {
                        return elements.get(0);
                    }
                    SbtNode tuple = new SbtNode(SbtNode.Kind.TUPLE, token.line, token.column);
                    tuple.args = elements;
                    return tuple;
                }
                if (token.isPunct("{")) {
                    return block();
                }
                issues.add(SbtIssue.warning(fileName, token.line, token.column,
                        "意外的记号 '" + token.text + "'"));
                next();
                SbtNode node = new SbtNode(SbtNode.Kind.SKIPPED, token.line, token.column);
                node.text = token.text;
                return node;
            }
            case IDENT: {
                String text = token.text;
                if (SbtKeywords.isBooleanLiteral(text)) {
                    next();
                    SbtNode node = new SbtNode(SbtNode.Kind.BOOL, token.line, token.column);
                    node.text = text;
                    return node;
                }
                if ("new".equals(text)) {
                    next();
                    // new Foo(args) / new Foo { ... }
                    StringBuilder typeName = new StringBuilder();
                    while (peek().is(SbtToken.Kind.IDENT) || peek().isPunct(".") || peek().isPunct("[")) {
                        if (peek().isPunct("[")) {
                            skipBalanced("[", "]");
                            continue;
                        }
                        typeName.append(peek().text);
                        next();
                    }
                    List<SbtNode> args = (peek().isPunct("(") || peek().isPunct("{"))
                            ? argumentList() : new ArrayList<>();
                    return SbtNode.call(null, "new " + typeName, args, token.line, token.column);
                }
                if (SbtKeywords.isControlKeyword(text)) {
                    return skippedStatement(text);
                }
                next();
                if (peek().isPunct("(") || peek().isPunct("{")) {
                    List<SbtNode> args = argumentList();
                    return SbtNode.call(null, text, args, token.line, token.column);
                }
                return SbtNode.ident(text, token.line, token.column);
            }
            case SYMBOL: {
                // 一元前缀：-x / !x / ~x
                if ("-".equals(token.text) || "!".equals(token.text) || "~".equals(token.text)
                        || "+".equals(token.text)) {
                    next();
                    SbtNode operand = postfix();
                    return SbtNode.infix(null, "unary" + token.text, operand, token.line, token.column);
                }
                if ("*".equals(token.text)) {
                    // import a.b.* 之类：当作通配符
                    next();
                    SbtNode node = new SbtNode(SbtNode.Kind.IDENT, token.line, token.column);
                    node.name = "*";
                    node.text = "*";
                    return node;
                }
                issues.add(SbtIssue.warning(fileName, token.line, token.column,
                        "意外的运算符 '" + token.text + "'"));
                next();
                SbtNode node = new SbtNode(SbtNode.Kind.SKIPPED, token.line, token.column);
                node.text = token.text;
                return node;
            }
            case NEWLINE:
                next();
                return primary();
            default: {
                next();
                SbtNode node = new SbtNode(SbtNode.Kind.SKIPPED, token.line, token.column);
                node.text = token.text;
                return node;
            }
        }
    }

    private SbtNode block() {
        SbtToken brace = peek();
        expect("{");
        SbtNode node = new SbtNode(SbtNode.Kind.BLOCK, brace.line, brace.column);
        node.args = statementList(false);
        expect("}");
        return node;
    }

    private List<SbtNode> argumentList() {
        if (peek().isPunct("{")) {
            List<SbtNode> args = new ArrayList<>();
            args.add(block());
            return args;
        }
        SbtToken open = peek();
        expect("(");
        List<SbtNode> args = expressionList(")");
        if (!peek().isPunct(")")) {
            issues.add(SbtIssue.warning(fileName, open.line, open.column, "实参列表没有闭合"));
        } else {
            next();
        }
        return args;
    }

    private List<SbtNode> expressionList(String terminator) {
        List<SbtNode> elements = new ArrayList<>();
        skipSeparators();
        if (peek().isPunct(terminator)) {
            return elements;
        }
        while (true) {
            elements.add(expression());
            skipSeparators();
            if (peek().isPunct(",")) {
                next();
                skipSeparators();
                // 尾随逗号
                if (peek().isPunct(terminator)) {
                    break;
                }
                continue;
            }
            break;
        }
        return elements;
    }

    private void skipBalanced(String open, String close) {
        if (!peek().isPunct(open)) {
            return;
        }
        int depth = 0;
        while (!peek().is(SbtToken.Kind.EOF)) {
            if (peek().isPunct(open)) {
                depth++;
            } else if (peek().isPunct(close)) {
                depth--;
                if (depth == 0) {
                    next();
                    return;
                }
            }
            next();
        }
    }

    private void skipSoftNewlines() {
        while (peek().is(SbtToken.Kind.NEWLINE)) {
            next();
        }
    }

    private void skipSeparators() {
        while (peek().is(SbtToken.Kind.NEWLINE) || peek().isPunct(";")) {
            next();
        }
    }

    private boolean canStartExpression(SbtToken token) {
        switch (token.kind) {
            case STRING:
            case NUMBER:
            case CHAR:
            case IDENT:
                return true;
            case PUNCT:
                return token.isPunct("(") || token.isPunct("{");
            case SYMBOL:
                return "-".equals(token.text) || "!".equals(token.text) || "~".equals(token.text);
            default:
                return false;
        }
    }

    // ------------------------------------------------------------ 记号流工具

    private SbtToken peek() {
        return peek(0);
    }

    private SbtToken peek(int offset) {
        int target = pos + offset;
        if (target < 0) {
            target = 0;
        }
        if (target >= tokens.size()) {
            return tokens.get(tokens.size() - 1);
        }
        return tokens.get(target);
    }

    private SbtToken next() {
        SbtToken token = peek();
        if (pos < tokens.size() - 1) {
            pos++;
        }
        return token;
    }

    private void expect(String punct) {
        if (peek().isPunct(punct)) {
            next();
            return;
        }
        SbtToken token = peek();
        issues.add(SbtIssue.warning(fileName, token.line, token.column,
                "期望 '" + punct + "'，实际是 '" + token.text + "'"));
    }
}
