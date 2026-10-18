package com.scala.android.sbt;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * sbt/Scala 的关键字与运算符表。
 *
 * <p>解析器只依赖这里的一张张表，不依赖编译器；新增 sbt 关键字时改这里即可。
 */
public final class SbtKeywords {

    private SbtKeywords() {
    }

    /** 赋值类运算符（sbt 的 setting 定义语法）。 */
    private static final Set<String> ASSIGN_OPERATORS = new HashSet<>(Arrays.asList(
            ":=", "+=", "++=", "-=", "--=", "~=", "<<=", "<+=", "+=", "*=", "/=", "%=", "&=", "|=", "^="
    ));

    /** 可以当"中缀运算符"读的字母标识符（Scala 允许字母运算符，sbt 里常见这几个）。 */
    private static final Set<String> INFIX_WORDS = new HashSet<>(Arrays.asList(
            "at", "in", "extends", "with", "to", "until", "by", "eq", "ne",
            "or", "and", "union", "diff", "intersect"
    ));

    /** 语句开头的变形关键字。 */
    private static final Set<String> STATEMENT_KEYWORDS = new HashSet<>(Arrays.asList(
            "import", "package", "class", "trait", "object", "case", "type"
    ));

    /** 控制流关键字：构建脚本里出现时按"整段原样跳过"处理。 */
    private static final Set<String> CONTROL_KEYWORDS = new HashSet<>(Arrays.asList(
            "if", "else", "while", "for", "do", "try", "catch", "finally", "throw", "return", "match"
    ));

    /** 定义了 val / def 的关键字。 */
    private static final Set<String> DEFINITION_KEYWORDS = new HashSet<>(Arrays.asList(
            "val", "var", "def"
    ));

    public static boolean isAssignOperator(String text) {
        return text != null && ASSIGN_OPERATORS.contains(text);
    }

    public static boolean isInfixWord(String text) {
        return text != null && INFIX_WORDS.contains(text);
    }

    public static boolean isStatementKeyword(String text) {
        return text != null && STATEMENT_KEYWORDS.contains(text);
    }

    public static boolean isControlKeyword(String text) {
        return text != null && CONTROL_KEYWORDS.contains(text);
    }

    public static boolean isDefinitionKeyword(String text) {
        return text != null && DEFINITION_KEYWORDS.contains(text);
    }

    /** 布尔/空字面量。 */
    public static boolean isBooleanLiteral(String text) {
        return "true".equals(text) || "false".equals(text);
    }

    /**
     * setting 的键路径规范化：{@code ThisBuild / organization} → {@code ThisBuild/organization}。
     *
     * <p>sbt 的 scope 是 {@code (project, config, task)} 三元组，这里只保留可读的路径形式，
     * 求值时再按 {@code /} 分段解释（第一段可能是 scope，最后一段才是键名）。
     */
    public static String canonicalKey(String path) {
        if (path == null) {
            return "";
        }
        return path.replace(" ", "").replace("/", "/");
    }

    /** 键路径的最后一段（真正的键名）。 */
    public static String keyName(String canonicalKey) {
        if (canonicalKey == null) {
            return "";
        }
        int index = canonicalKey.lastIndexOf('/');
        return index < 0 ? canonicalKey : canonicalKey.substring(index + 1);
    }

    /** 键路径的 scope 部分（可能是 {@code ThisBuild}、{@code Compile}、{@code Compile/test}）。 */
    public static String keyScope(String canonicalKey) {
        if (canonicalKey == null) {
            return "";
        }
        int index = canonicalKey.lastIndexOf('/');
        return index < 0 ? "" : canonicalKey.substring(0, index);
    }
}
