package com.tiecode.ktcplugin.lang.kt;

import com.rapid.api.lang.engine.completion.CompletionParams;
import com.rapid.framework.data.document.Position;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 补全用的纯文本工具：前缀截取、接收者识别、自动导包位置、前缀匹配。
 *
 * <p>刻意与 {@link KotlinCompletionProvider} 分开：这些逻辑不依赖结绳运行时，
 * 可以脱离编辑器直接做单元测试（宿主基类在 API jar 里是存根，无法在桌面 JVM 上实例化子类）。
 *
 * <h3>一条统一约定：一切判断都以「光标之前的文本」为准</h3>
 * 那个文本由 {@link #cursorPrefixText(CompletionParams)} 给出，取法有优先级：
 * <ol>
 *   <li>{@code lineText + position}——{@code position} 是光标真实落点，最准；</li>
 *   <li>退化到 {@code partial}——它不含刚敲的空格，所以只能兜底，不能优先。</li>
 * </ol>
 * 早先版本把 {@code partial} 放在第一位，结果「敲完 {@code val } 不弹补全」这条规则
 * 会被 {@code partial = "val"} 绕过（前缀算成 {@code val}、又不是空白结尾），
 * 补全框照旧糊上来。这个坑记在这里，别再踩回去。
 */
public final class KotlinTextUtils {

    /**
     * 行尾的 {@code recv.optPrefix} / {@code recv?.optPrefix} 形态，用于识别成员访问。
     *
     * <p>安全调用 {@code ?.} 与普通点号同等看待：{@code list?.si} 也该按成员访问处理，
     * 否则片段与关键字会混进候选里。
     */
    private static final Pattern RECEIVER = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\?\\.|\\.)\\s*([A-Za-z_][A-Za-z0-9_]*)?$");

    /**
     * 「后面必然跟一个名字 / 表达式」的关键字。
     *
     * <p>在这些字之后敲空格、换行，用户想的是「开始写东西」，不是在找补全候选。
     */
    private static final Set<String> DECLARATION_KEYWORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // 声明
                    "val", "var", "fun", "class", "interface", "object", "typealias",
                    "data", "enum", "sealed", "annotation", "companion", "constructor",
                    "init", "import", "package",
                    // 可见性 / 修饰符
                    "private", "protected", "public", "internal", "open", "override",
                    "abstract", "final", "lateinit", "const", "suspend", "inline",
                    "noinline", "crossinline", "reified", "vararg", "operator", "infix",
                    "tailrec", "external", "expect", "actual", "inner", "by",
                    // 控制流
                    "if", "else", "when", "while", "for", "do", "return", "throw",
                    "catch", "finally", "try", "in", "is", "as"
            )));

    private KotlinTextUtils() {
    }

    /** 标识符字符：Kotlin 标识符只允许字母、数字、下划线与 {@code $}。 */
    public static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    // ------------------------------------------------------------------
    // 光标上下文
    // ------------------------------------------------------------------

    /**
     * 取「光标之前的文本」。所有上下文判断都走这里，保证口径一致。
     *
     * <p>优先按 {@code lineText + position} 切；没有可用 position 时退回 {@code partial}。
     */
    public static String cursorPrefixText(CompletionParams params) {
        if (params == null) {
            return "";
        }
        if (hasUsablePosition(params)) {
            String lineText = params.lineText;
            int column = clamp(params.position.character, 0, lineText.length());
            return lineText.substring(0, column);
        }
        return params.partial == null ? "" : params.partial;
    }

    /** {@code lineText} 与 {@code position} 是否都可用且不越界。 */
    public static boolean hasUsablePosition(CompletionParams params) {
        return params != null
                && params.lineText != null
                && params.position != null
                && params.position.character >= 0
                && params.position.character <= params.lineText.length();
    }

    /**
     * 取用于过滤补全的前缀（光标前连续标识符）。
     *
     * <p>光标前以空白结尾时自然得到空串——那表示用户刚敲了空格，
     * 当前并没有「正在输入的标识符」。
     */
    public static String prefixOf(CompletionParams params) {
        String text = cursorPrefixText(params);
        int cursor = text.length();
        // 光标紧邻的前一个字符必须是标识符字符，否则「当前没有正在输入的标识符」。
        // 注意不能先跳过空白再往前找：`val ` 会被错误地算成前缀 val，
        // 那样「空格后不弹补全」就直接失效了。
        if (cursor == 0 || !isIdentifierPart(text.charAt(cursor - 1))) {
            return "";
        }
        int start = cursor;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, cursor);
    }

    /** 取字符串末尾的标识符片段；{@code null} 或没有标识符时返回空串。 */
    public static String tailIdentifier(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int end = text.length();
        while (end > 0 && !isIdentifierPart(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    /**
     * 光标是否处在一个 {@code xxx.} 之后（成员访问）。
     *
     * @return 接收者名字；不是成员访问时返回 {@code null}
     */
    public static String receiverOf(CompletionParams params) {
        if (params == null) {
            return null;
        }
        String before = cursorPrefixText(params);
        // 先吃掉光标前正在输入的标识符片段，让正则落在 "recv." 上。
        int end = before.length();
        while (end > 0 && isIdentifierPart(before.charAt(end - 1))) {
            end--;
        }
        Matcher matcher = RECEIVER.matcher(before.substring(0, end));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /** 兼容旧调用：按行文本 + 光标位置识别接收者。 */
    public static String receiverOf(String lineText, Position position) {
        CompletionParams params = new CompletionParams();
        params.lineText = lineText;
        params.position = position;
        return receiverOf(params);
    }

    /**
     * 光标前是否以空白结尾——即用户刚敲了空格 / 换行在排版，而不是在写标识符。
     *
     * <p>这是「空格之后不弹补全」的判据，比只看「前一个词是不是关键字」更宽：
     * 它同时覆盖关键字（{@code val }）与运算符（{@code x = }）后面的空格，
     * 又不会误伤逗号（{@code foo(a, }）和左括号（{@code foo(}）——那两种位置
     * 由 {@link #isAfterOpenBracket} 与调用方另行放行。
     */
    public static boolean endsWithWhitespace(CompletionParams params) {
        if (params == null) {
            return false;
        }
        if (hasUsablePosition(params)) {
            String lineText = params.lineText;
            int column = clamp(params.position.character, 0, lineText.length());
            return column == 0 || Character.isWhitespace(lineText.charAt(column - 1));
        }
        String partial = params.partial;
        if (partial != null && !partial.isEmpty()) {
            return Character.isWhitespace(partial.charAt(partial.length() - 1));
        }
        return false;
    }

    /**
     * 光标是否在<b>没有闭合的圆/方括号内部</b>（{@code foo(} / {@code foo(a, } / {@code arr[}）。
     *
     * <p>这种位置即使前缀为空、即使刚敲了空格，候选也是有用的——用户在写下一个参数。
     * 这是「空格后不弹」规则唯一的例外，用来把 {@code foo(a, } 与 {@code val } 区分开：
     * 前者括号没闭合，后者不在括号里。
     */
    public static boolean isInsideUnclosedBracket(CompletionParams params) {
        String text = cursorPrefixText(params);
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            }
        }
        return depth > 0;
    }

    /**
     * 光标是否紧跟在一个左括号 / 左方括号之后（{@code foo(} / {@code arr[}）。
     *
     * <p>这种位置即使前缀为空也值得给候选——用户正要写第一个参数。
     */
    public static boolean isAfterOpenBracket(CompletionParams params) {
        Character ch = lastNonWhitespaceBeforeCursor(params);
        return ch != null && (ch == '(' || ch == '[');
    }

    /** 光标前最后一个非空白字符；拿不到返回 {@code null}。 */
    private static Character lastNonWhitespaceBeforeCursor(CompletionParams params) {
        String text = cursorPrefixText(params);
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 光标是否正处在一个<b>声明 / 控制流关键字之后</b>的空格上。
     *
     * <p>典型场景：敲完 {@code val } 想写变量名。{@link #endsWithWhitespace} 已经能挡住
     * 大多数情况，这个方法用于需要区分「关键字后」与「逗号后」的场合。
     */
    public static boolean isAtDeclarationKeyword(CompletionParams params) {
        String text = cursorPrefixText(params);
        int end = text.length();
        if (end == 0 || !Character.isWhitespace(text.charAt(end - 1))) {
            return false;
        }
        int i = end;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        int tokenEnd = i;
        while (i > 0 && isIdentifierPart(text.charAt(i - 1))) {
            i--;
        }
        if (i == tokenEnd) {
            return false;   // 空白前面不是标识符（是逗号、括号、运算符…）
        }
        return DECLARATION_KEYWORDS.contains(text.substring(i, tokenEnd));
    }

    /** 兼容旧调用：按行文本 + 位置 + 片段判断。 */
    public static boolean isAtDeclarationKeyword(String lineText, Position position, String partial) {
        CompletionParams params = new CompletionParams();
        params.lineText = lineText;
        params.position = position;
        params.partial = partial;
        return isAtDeclarationKeyword(params);
    }

    // ------------------------------------------------------------------
    // 自动导包
    // ------------------------------------------------------------------

    /**
     * 求「自动导包」应该把 {@code import} 插到第几行。
     *
     * <p>规则与 scala 控制台插件一致：跳过开头的空行、{@code package} 行与已有的
     * {@code import} 行，插在它们之后的第一行；一个都没有就插到文件最前面。
     */
    public static int importInsertLine(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String[] lines = content.split("\n", -1);
        int insertLine = 0;
        boolean packageSeen = false;
        boolean importSeen = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!packageSeen && trimmed.startsWith("package ")) {
                packageSeen = true;
                insertLine = i + 1;
                continue;
            }
            if (trimmed.startsWith("import ")) {
                importSeen = true;
                insertLine = i + 1;
                continue;
            }
            // 一旦出现声明就停。
            break;
        }
        // 没有任何 import 时：包声明之后常常还有一个空行，insertLine 会停在空行上，
        // 于是新 import 与 package 之间被挤出一个空行。往前收一格让排版更整齐。
        if (!importSeen && insertLine > 0 && insertLine - 1 < lines.length
                && lines[insertLine - 1].trim().isEmpty()) {
            insertLine--;
        }
        return insertLine;
    }

    /**
     * 该全限定名是否本来就<b>不需要</b> import。
     *
     * <p>Kotlin 默认导入 {@code kotlin.*} 及其若干子包、{@code java.lang.*} 等。
     * 对这些名字再插一行 import 是多余的噪音。
     */
    public static boolean isDefaultImported(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return true;
        }
        if (qualifiedName.startsWith("kotlin.") || qualifiedName.startsWith("java.lang.")) {
            return true;
        }
        return qualifiedName.indexOf('.') < 0;
    }

    /** 文件里是否已经写了这条 import。 */
    public static boolean hasImport(String content, String qualifiedName) {
        if (content == null || qualifiedName == null || qualifiedName.isEmpty()) {
            return false;
        }
        return Pattern.compile(
                        "^\\s*import\\s+" + Pattern.quote(qualifiedName) + "\\s*$",
                        Pattern.MULTILINE)
                .matcher(content)
                .find();
    }

    // ------------------------------------------------------------------
    // 匹配
    // ------------------------------------------------------------------

    /** 前缀匹配（大小写不敏感）；空前缀视为全部命中。 */
    public static boolean matches(String label, String prefix) {
        if (label == null) {
            return false;
        }
        if (prefix == null || prefix.isEmpty()) {
            return true;
        }
        return label.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
