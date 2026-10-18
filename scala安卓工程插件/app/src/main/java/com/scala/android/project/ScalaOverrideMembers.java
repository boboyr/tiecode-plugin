package com.scala.android.project;

import com.scala.android.compiler.ScalaAnalysisApi;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * "覆写成员"补全：在类/特质体里给出一份可以插入编辑器的 {@code override def} 骨架。
 *
 * <p>宿主本体的编辑器没有这个功能，而 Scala 里继承/实现接口后要写的方法名只能靠记，
 * 所以这里自己做：
 *
 * <h3>做法</h3>
 * 光标在 {@code class A extends B with C} 的体里时，拼一份探针源码：
 *
 * <pre>
 * &lt;原文件的 package / import 行原样带上&gt;
 * class __ScalaOverrideProbe extends B with C:
 *   def __probe = this.     ← 在这后面取补全
 * </pre>
 *
 * 交给 dotty 的交互式补全，拿到的就是"B 与 C 的全部成员"（探针类自己是空的，
 * 所以这些成员一定是继承来的），再按签名拼成 {@code override def ... = ???}。
 *
 * <p>结果按「父类型 + classpath」缓存：同一份继承关系只分析一次。
 */
public final class ScalaOverrideMembers {

    /**
     * 不该出现在"覆写成员"里的名字，两类：
     * <ul>
     *   <li>{@code Any}/{@code Object} 的公共成员：谁都继承，列出来只是刷屏；</li>
     *   <li>宿主 {@code BaseObject} 里声明成 <b>final</b> 的工具方法（getActivity /
     *       getService / openConsole …）：它们根本不允许覆写，列出来会生成编不过的代码。</li>
     * </ul>
     */
    private static final Set<String> UNIVERSAL = new HashSet<>(Arrays.asList(
            // Any / Object
            "toString", "hashCode", "equals", "getClass", "wait", "notify", "notifyAll",
            "clone", "finalize", "synchronized", "eq", "ne", "##", "asInstanceOf",
            "isInstanceOf", "==", "!=", "->", "ensuring", "formatted", "pipe", "tap", "nn",
            // BaseObject / QuickOperator 的 final 成员（不可覆写）
            "getFramework", "getService", "getData", "getActivity", "getAppCompatActivity",
            "getApplicationContext", "getSharedPreferences", "getSystemPath",
            "getAppThemeService", "getEditorThemeService", "getIconService",
            "getPlatformLogger", "getProjectFramework", "runUI", "openConsole",
            "openFileSelector", "openBrowser", "startActivityForResult", "openSettingsPage"
    ));

    private static final Pattern CLASS_HEADER = Pattern.compile(
            "^\\s*(?:case\\s+)?(?:abstract\\s+)?(?:sealed\\s+)?(?:final\\s+)?"
                    + "(?:class|trait|object|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private static final Map<String, List<Item>> CACHE = new ConcurrentHashMap<>();

    private static final int MAX_ITEMS = 60;

    private ScalaOverrideMembers() {
    }

    /** 一条可插入的覆写骨架。 */
    public static final class Item {
        /** 成员名（补全弹窗里显示的就是它）。 */
        public final String name;
        /** 签名（弹窗里的说明文字）。 */
        public final String signature;
        /** 插入编辑器的代码（带 {@code $0} 光标位）。 */
        public final String insertText;
        /** 排序键。 */
        public final String sortText;

        private Item(String name, String signature, String insertText, String sortText) {
            this.name = name;
            this.signature = signature;
            this.insertText = insertText;
            this.sortText = sortText;
        }
    }

    /**
     * 光标是否在"可以实现继承成员"的位置：某个 class/trait/object 头之后，并且在它的体里。
     */
    public static boolean isInheritanceContext(String content, int offset) {
        return header(content, offset) != null && isMemberLevelLine(content, offset);
    }

    /**
     * 光标所在行是不是"类成员级"的位置。
     *
     * <p>只有成员级才给覆写候选：方法体里（{@code Toast.makeText(...)}）、表达式里
     * 都不该刷出一堆 {@code override def}。判据：光标左边这一行去掉修饰符/关键字后
     * 只剩一个标识符（正在输入的成员名）或者什么都没有。
     */
    private static boolean isMemberLevelLine(String content, int offset) {
        int safe = Math.min(offset, content.length());
        int lineStart = content.lastIndexOf('\n', safe - 1) + 1;
        String text = content.substring(lineStart, safe).trim();
        if (text.isEmpty()) {
            return true;
        }
        String[] words = text.split("\\s+");
        boolean sawKeyword = false;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            boolean last = i == words.length - 1;
            if (isModifier(word)) {
                sawKeyword = true;
                if (last) {
                    return true; // 只敲了 override / def 这种
                }
                continue;
            }
            if (last && isPlainName(word)) {
                return true;
            }
            return false;
        }
        return sawKeyword;
    }

    private static boolean isModifier(String word) {
        switch (word) {
            case "override":
            case "final":
            case "implicit":
            case "lazy":
            case "private":
            case "protected":
            case "def":
            case "val":
            case "var":
            case "given":
                return true;
            default:
                return false;
        }
    }

    /** 取当前类的父类型成员（附带缓存）；拿不到就返回空表。 */
    public static List<Item> items(String content, int offset, List<File> classpath) {
        Header header = header(content, offset);
        if (header == null || header.supertypes.isEmpty()) {
            return Collections.emptyList();
        }
        String key = header.supertypes + "@" + classpathKey(classpath);
        List<Item> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<Item> items = build(content, header, classpath);
        if (items != null) {
            synchronized (CACHE) {
                if (CACHE.size() > 32) {
                    CACHE.clear();
                }
                CACHE.put(key, items);
            }
        }
        return items == null ? Collections.<Item>emptyList() : items;
    }

    // ------------------------------------------------------------ 探针 + 分析

    private static List<Item> build(String content, Header header, List<File> classpath) {
        if (classpath == null || !ScalaAnalysisApi.isWarm(classpath)) {
            return null; // 冷启动时不做，避免阻塞
        }
        String probe = probeSource(content, header);
        int offset = probe.indexOf(PROBE_MARK);
        if (offset < 0) {
            return null;
        }
        probe = probe.substring(0, offset) + probe.substring(offset + PROBE_MARK.length());
        List<ScalaAnalysisApi.Suggestion> suggestions;
        try {
            suggestions = ScalaAnalysisApi.complete(probe, PROBE_URI, offset, classpath);
        } catch (Throwable ignored) {
            return null;
        }
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 文件里已经写过的成员不再提示（写成 getId 之后就别再劝你覆写 getId）
        Set<String> defined = definedMembers(content);
        for (ScalaAnalysisApi.Suggestion suggestion : suggestions) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            if (suggestion == null || suggestion.label == null) {
                continue;
            }
            String name = suggestion.label;
            if (name.isEmpty() || name.charAt(0) == '$' || UNIVERSAL.contains(name)) {
                continue;
            }
            if (!isPlainName(name) || !seen.add(name) || defined.contains(name)) {
                continue;
            }
            Item item = toItem(name, suggestion.detail);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** 源码里已经定义过的成员名（def / val / var）。 */
    private static Set<String> definedMembers(String content) {
        Set<String> defined = new HashSet<>();
        java.util.regex.Matcher matcher = DEFINITION.matcher(content);
        while (matcher.find()) {
            defined.add(matcher.group(1));
        }
        return defined;
    }

    private static final Pattern DEFINITION = Pattern.compile(
            "(?m)^\\s*(?:override\\s+)?(?:final\\s+)?(?:def|val|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private static final String PROBE_MARK = "\u0000";
    private static final URI PROBE_URI = URI.create("file:///__scala_override_probe.scala");

    /** 拼探针源码：沿用原文件的 package/import，再挂一个只继承父类型的空类。 */
    private static String probeSource(String content, Header header) {
        StringBuilder probe = new StringBuilder();
        for (String line : header.prefixLines) {
            probe.append(line).append('\n');
        }
        probe.append("class __ScalaOverrideProbe extends ");
        probe.append(join(header.supertypes, " with "));
        probe.append(":\n  def __probe = this.").append(PROBE_MARK).append('\n');
        return probe.toString();
    }

    // ------------------------------------------------------------ 类头解析

    private static final class Header {
        final List<String> supertypes;
        final List<String> prefixLines;

        Header(List<String> supertypes, List<String> prefixLines) {
            this.supertypes = supertypes;
            this.prefixLines = prefixLines;
        }
    }

    /**
     * 找到光标所属的 class/trait/object 头，取出父类型和"要带进探针的前缀行"
     * （package 与 import）。
     */
    private static Header header(String content, int offset) {
        if (content == null || content.isEmpty() || offset < 0) {
            return null;
        }
        int safe = Math.min(offset, content.length());
        String before = content.substring(0, safe);
        String[] lines = before.split("\n", -1);
        int cursorLine = lines.length - 1;

        // 从光标往上找最近的一个类头
        int headerLine = -1;
        for (int i = cursorLine; i >= 0; i--) {
            if (CLASS_HEADER.matcher(lines[i]).find()) {
                headerLine = i;
                break;
            }
        }
        if (headerLine < 0) {
            return null;
        }
        // 类头必须是"包含"光标的：同一行（光标在类头行里）或光标在更后面的行
        List<String> supertypes = supertypes(lines[headerLine]);
        if (supertypes.isEmpty()) {
            return null;
        }
        List<String> prefix = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                prefix.add(line);
            }
        }
        return new Header(supertypes, prefix);
    }

    /** 从类头行里取 {@code extends A with B}（也认 Scala 3 的 {@code :} 结尾）。 */
    private static List<String> supertypes(String headerLine) {
        List<String> result = new ArrayList<>();
        int index = headerLine.indexOf("extends");
        if (index < 0) {
            return result;
        }
        String rest = headerLine.substring(index + "extends".length());
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        rest = rest.replace('{', ' ').replace('}', ' ').trim();
        for (String part : rest.split("\\s+with\\s+")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            // 去掉构造参数：class A extends Base(x, y) → Base
            int paren = name.indexOf('(');
            if (paren > 0) {
                name = name.substring(0, paren).trim();
            }
            if (!name.isEmpty()) {
                result.add(name);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 生成骨架

    /**
     * 按 dotty 的描述拼 {@code override} 骨架。
     *
     * <p>描述形如 {@code (x$0: DataHolder): Unit} / {@code : String} / {@code => Drawable} /
     * {@code (): String} / {@code [B](implicit ord: Ordering[B]): Char}。
     */
    private static Item toItem(String name, String description) {
        String signature = description == null ? "" : description.trim();
        String typeParams = "";
        String params = "";
        String resultType = "";

        String rest = signature;
        if (rest.startsWith("[")) {
            int close = matching(rest, '[', ']');
            if (close < 0) {
                rest = "";
            } else {
                typeParams = rest.substring(0, close + 1);
                rest = rest.substring(close + 1).trim();
            }
        }
        if (rest.startsWith("(")) {
            int close = matching(rest, '(', ')');
            if (close < 0) {
                rest = "";
            } else {
                params = cleanParams(rest.substring(0, close + 1));
                rest = rest.substring(close + 1).trim();
            }
        }
        // dotty 的描述有三种形态：
        //   "(x$0: DataHolder): Unit" / "(): String"  → 有参数列表的方法
        //   ": String"                                 → 无参方法
        //   "Int"                                      → 字段（val）
        boolean hasParams = !params.isEmpty();
        boolean isField = false;
        if (rest.startsWith(":")) {
            resultType = rest.substring(1).trim();
        } else if (rest.startsWith("=>")) {
            resultType = rest.substring(2).trim();
        } else if (!rest.isEmpty()) {
            resultType = rest;
            isField = !hasParams;
        }
        if (resultType.isEmpty()) {
            resultType = "Unit";
        }
        if (resultType.contains("?") || resultType.contains("__ScalaOverrideProbe")
                || resultType.contains(".this")) {
            // 存在类型 / 依赖探针类本身的路径类型：统一退到 Any，别让生成的代码编不过
            resultType = "Any";
        }
        if (params.contains("__ScalaOverrideProbe")) {
            return null; // 参数里带探针类型就没有可用的骨架，干脆不列
        }

        String keyword = isField ? "override val " : "override def ";
        String insert = keyword + name + typeParams + params + ": " + resultType + " = $0";
        String label = isField ? name : name;
        return new Item(label, "覆写 " + label + signature, insert, "0" + label);
    }

    /** {@code (x$0: DataHolder, x$1: Int)} → {@code (arg0: DataHolder, arg1: Int)}。 */
    private static String cleanParams(String params) {
        String cleaned = params.replaceAll("x\\$(\\d+)", "arg$1");
        return cleaned;
    }

    private static int matching(String text, char open, char close) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isPlainName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0 ? !Character.isJavaIdentifierStart(c) : !Character.isJavaIdentifierPart(c)) {
                return false;
            }
        }
        return true;
    }

    private static String classpathKey(List<File> classpath) {
        StringBuilder builder = new StringBuilder();
        for (File file : classpath) {
            if (file != null) {
                builder.append(file.getAbsolutePath()).append(File.pathSeparatorChar);
            }
        }
        return Integer.toHexString(builder.toString().hashCode());
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
    }

    /** 便于以后调试：当前能识别到的父类型。 */
    static List<String> debugSupertypes(String content, int offset) {
        Header header = header(content, offset);
        return header == null ? Collections.<String>emptyList() : header.supertypes;
    }
}
