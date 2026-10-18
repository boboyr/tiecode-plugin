package com.scala.android.sbt;

import java.util.ArrayList;
import java.util.List;

/**
 * sbt 脚本的语法树节点。
 *
 * <p>刻意做成"一个类 + 若干字段"而不是几十个子类：构建脚本的语法形态很集中，
 * 求值器只需要按 {@link Kind} 分支，代码量小、也方便打印出来做调试。
 */
public final class SbtNode {

    public enum Kind {
        /** 标识符 / 无参引用，如 {@code baseDirectory}、{@code Test}。 */
        IDENT,
        /** 字符串字面量。 */
        STRING,
        /** 数字字面量。 */
        NUMBER,
        /** 布尔字面量。 */
        BOOL,
        /** 选择：{@code target.name}。 */
        SELECT,
        /** 调用：{@code target.name(args...)} 或 {@code name(args...)}。 */
        CALL,
        /** 中缀：{@code left op right}（含 {@code /}、{@code %}、{@code at}）。 */
        INFIX,
        /** 赋值：{@code left := right}（op 见 {@link SbtKeywords#isAssignOperator}）。 */
        ASSIGN,
        /** 元组 / 参数列表 {a, b, c}。 */
        TUPLE,
        /** 块 {@code { ... }}（内容为语句列表）。 */
        BLOCK,
        /** 定义：{@code val x = e} / {@code lazy val x = e} / {@code def x = e}。 */
        DEFN,
        /** 无法解释、按原文整段跳过的东西（控制流等）。 */
        SKIPPED
    }

    public Kind kind;
    /** IDENT/SELECT/CALL/INFIX/ASSIGN/DEFN 的名字或运算符。 */
    public String name;
    /** STRING/NUMBER/BOOL/SKIPPED 的字面量文本。 */
    public String text;
    /** DEFN：是否 lazy；STRING：是否插值。 */
    public boolean flag;
    /** SELECT/CALL 的接收者；INFIX/ASSIGN 的左值。 */
    public SbtNode target;
    /** INFIX/ASSIGN 的右值。 */
    public SbtNode right;
    /** CALL 的实参 / TUPLE 的元素 / BLOCK 的语句。 */
    public List<SbtNode> args;
    public int line;
    public int column;

    public SbtNode(Kind kind, int line, int column) {
        this.kind = kind;
        this.line = line;
        this.column = column;
    }

    public static SbtNode ident(String name, int line, int column) {
        SbtNode node = new SbtNode(Kind.IDENT, line, column);
        node.name = name;
        node.text = name;
        return node;
    }

    public static SbtNode string(String text, int line, int column) {
        SbtNode node = new SbtNode(Kind.STRING, line, column);
        node.text = text;
        return node;
    }

    public static SbtNode call(SbtNode target, String name, List<SbtNode> args, int line, int column) {
        SbtNode node = new SbtNode(Kind.CALL, line, column);
        node.target = target;
        node.name = name;
        node.args = args == null ? new ArrayList<>() : args;
        return node;
    }

    public static SbtNode select(SbtNode target, String name, int line, int column) {
        SbtNode node = new SbtNode(Kind.SELECT, line, column);
        node.target = target;
        node.name = name;
        return node;
    }

    public static SbtNode infix(SbtNode left, String op, SbtNode right, int line, int column) {
        SbtNode node = new SbtNode(Kind.INFIX, line, column);
        node.target = left;
        node.name = op;
        node.right = right;
        return node;
    }

    public List<SbtNode> args() {
        if (args == null) {
            args = new ArrayList<>();
        }
        return args;
    }

    public boolean is(Kind other) {
        return kind == other;
    }

    public boolean isIdent(String value) {
        return kind == Kind.IDENT && value.equals(name);
    }

    public boolean isInfix(String op) {
        return kind == Kind.INFIX && op.equals(name);
    }

    public boolean isCallTo(String method) {
        return kind == Kind.CALL && method.equals(name);
    }

    /** 调试用：把子树还原成接近源码的一行文本。 */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        describe(builder, 0);
        return builder.toString();
    }

    private void describe(StringBuilder builder, int depth) {
        if (depth > 24) {
            builder.append("...");
            return;
        }
        switch (kind) {
            case IDENT:
            case NUMBER:
            case BOOL:
                builder.append(text);
                break;
            case STRING:
                builder.append('"').append(text).append('"');
                break;
            case SELECT:
                target.describe(builder, depth + 1);
                builder.append('.').append(name);
                break;
            case CALL:
                if (target != null) {
                    target.describe(builder, depth + 1);
                    builder.append('.');
                }
                builder.append(name).append('(');
                if (args != null) {
                    for (int i = 0; i < args.size(); i++) {
                        if (i > 0) {
                            builder.append(", ");
                        }
                        args.get(i).describe(builder, depth + 1);
                    }
                }
                builder.append(')');
                break;
            case INFIX:
                target.describe(builder, depth + 1);
                builder.append(' ').append(name).append(' ');
                if (right != null) {
                    right.describe(builder, depth + 1);
                }
                break;
            case ASSIGN:
                target.describe(builder, depth + 1);
                builder.append(' ').append(name).append(' ');
                if (right != null) {
                    right.describe(builder, depth + 1);
                }
                break;
            case TUPLE:
                builder.append('(');
                for (int i = 0; args != null && i < args.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    args.get(i).describe(builder, depth + 1);
                }
                builder.append(')');
                break;
            case BLOCK:
                builder.append("{ ");
                for (int i = 0; args != null && i < args.size(); i++) {
                    if (i > 0) {
                        builder.append("; ");
                    }
                    args.get(i).describe(builder, depth + 1);
                }
                builder.append(" }");
                break;
            case DEFN:
                if (flag) {
                    builder.append("lazy ");
                }
                builder.append(name);
                if (text != null && !text.isEmpty()) {
                    builder.append(" [").append(text).append(']');
                }
                builder.append(" = ");
                if (right != null) {
                    right.describe(builder, depth + 1);
                }
                break;
            case SKIPPED:
            default:
                builder.append(text == null ? "<skipped>" : text);
                break;
        }
    }
}
