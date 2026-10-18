package com.scala.android.sbt;

/**
 * sbt 构建脚本（build.sbt / *.sbt / project/*.sbt）的一个词法单元。
 *
 * <p>这一层刻意不依赖 Android / 结绳宿主 API，纯 Java 实现，因此可以在
 * 普通 JVM 上单独跑测试（见 {@code tests/}）。
 */
public final class SbtToken {

    public enum Kind {
        /** 标识符：普通标识符、反引号标识符、以及 {@code at/ in/ to} 这类字母运算符。 */
        IDENT,
        /** 运算符标识符：{@code := += ++= % %% / at -> =>} 等由符号字符组成的串。 */
        SYMBOL,
        /** 字符串字面量（含三引号与 s"..." 插值前缀）。 */
        STRING,
        /** 字符字面量 {@code 'a'}。 */
        CHAR,
        /** 数字字面量。 */
        NUMBER,
        /** {@code ( ) [ ] { } , . ; } */
        PUNCT,
        /** 换行（Scala 里是语句分隔符，缩进无关但换行有意义）。 */
        NEWLINE,
        /** 文件结束。 */
        EOF
    }

    public final Kind kind;
    /** 原文（字符串字面量是已去引号/已解转义的正文）。 */
    public final String text;
    /** 1 基行号。 */
    public final int line;
    /** 1 基列号。 */
    public final int column;
    /** STRING 专用：是否带 {@code s} / {@code f} / {@code raw} 插值前缀。 */
    public final boolean interpolated;
    /** STRING 专用：是否三引号。 */
    public final boolean tripleQuoted;

    public SbtToken(Kind kind, String text, int line, int column) {
        this(kind, text, line, column, false, false);
    }

    public SbtToken(Kind kind, String text, int line, int column,
                    boolean interpolated, boolean tripleQuoted) {
        this.kind = kind;
        this.text = text;
        this.line = line;
        this.column = column;
        this.interpolated = interpolated;
        this.tripleQuoted = tripleQuoted;
    }

    public boolean is(Kind other) {
        return kind == other;
    }

    /** 是否是内容为 {@code value} 的标识符。 */
    public boolean isIdent(String value) {
        return kind == Kind.IDENT && value != null && value.equals(text);
    }

    public boolean isPunct(String value) {
        return kind == Kind.PUNCT && value.equals(text);
    }

    public boolean isSymbol(String value) {
        return kind == Kind.SYMBOL && value.equals(text);
    }

    /** 运算符或标识符运算符都算“可当运算符用”。 */
    public boolean isOperator(String value) {
        return (kind == Kind.SYMBOL || kind == Kind.IDENT) && value.equals(text);
    }

    @Override
    public String toString() {
        return kind + "(" + text + ")@" + line + ":" + column;
    }
}
