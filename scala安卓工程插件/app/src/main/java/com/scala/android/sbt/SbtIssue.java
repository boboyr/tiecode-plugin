package com.scala.android.sbt;

/**
 * 解析/求值过程中的一条诊断信息（不阻断解析，收集起来在构建日志里提示）。
 */
public final class SbtIssue {

    public enum Level {
        WARNING, ERROR
    }

    public final Level level;
    public final String file;
    public final int line;
    public final int column;
    public final String message;

    public SbtIssue(Level level, String file, int line, int column, String message) {
        this.level = level;
        this.file = file;
        this.line = line;
        this.column = column;
        this.message = message;
    }

    public static SbtIssue warning(String file, int line, int column, String message) {
        return new SbtIssue(Level.WARNING, file, line, column, message);
    }

    public static SbtIssue error(String file, int line, int column, String message) {
        return new SbtIssue(Level.ERROR, file, line, column, message);
    }

    public String format() {
        StringBuilder builder = new StringBuilder();
        if (file != null) {
            builder.append(file);
        }
        if (line > 0) {
            builder.append(':').append(line);
            if (column > 0) {
                builder.append(':').append(column);
            }
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        return builder.append(level == Level.ERROR ? "错误: " : "警告: ").append(message).toString();
    }

    @Override
    public String toString() {
        return format();
    }
}
