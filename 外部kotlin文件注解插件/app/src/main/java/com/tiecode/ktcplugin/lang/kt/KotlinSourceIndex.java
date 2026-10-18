package com.tiecode.ktcplugin.lang.kt;

import com.rapid.api.Platform;
import com.rapid.api.lang.engine.completion.CompletionItemKind;
import com.rapid.api.project.Project;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Kotlin 源码符号索引。
 *
 * <p>结绳工程里「外部 kotlin 文件」是普通目录下的 {@code .kt} 文件；补全想有意义，
 * 至少要能提示工程内已经声明过的类、函数与顶层属性。真做这件事需要 kotlinc 的
 * 元数据解析，代价很大；这里用<b>正则扫描</b>做一个够用的近似：
 * <ul>
 *   <li>收录类 / 接口 / 对象 / 枚举 / 类型别名 / 函数 / 属性的声明，含构造参数属性；
 *       函数体内的局部 {@code val}/{@code var} 大多因为缩进被过滤，但不保证；</li>
 *   <li>不做类型推断、不解析 import，命中的名字一律作为候选提供；</li>
 *   <li>扫描结果按<b>时间间隔</b>缓存（不是按 mtime 逐个比对），
 *       距今 {@value #REFRESH_INTERVAL_MS} 毫秒内不重复扫盘，避免每次按键都遍历工程。</li>
 * </ul>
 *
 * <p>线程安全：状态读写都在 {@code lock} 内，但<b>扫盘在锁外</b>做
 * （见 {@link #snapshot(String)}）——扫描可能读到上千个文件，占着锁会让
 * {@code invalidate()} 这类宿主回调一起卡住。索引语义是「尽力而为」：
 * 扫描失败时返回上一次的快照（工程切换时则清空），而不是让补全抛异常。
 */
public final class KotlinSourceIndex {

    /** 单个文件超过该大小就不再解析（避免把生成代码读进内存）。 */
    private static final long MAX_FILE_BYTES = 512L * 1024L;

    /** 已解析的 Kotlin 文件数上限（只数 .kt/.kts，不被图片/依赖等非源码文件吃掉预算）。 */
    private static final int MAX_FILES = 4000;

    /** 目录枚举上限，防止误把整个 sdcard 扫一遍。 */
    private static final int MAX_DIRS = 2000;

    /** 遍历深度上限（相对工程根）。 */
    private static final int MAX_DEPTH = 8;

    /** 两次完整扫描之间的最小间隔。 */
    private static final long REFRESH_INTERVAL_MS = 5000L;

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)");

    /**
     * 注解及其参数，例如 {@code @JvmStatic}、{@code @Suppress("x")}、{@code @get:Rule}。
     *
     * <p>声明前带注解是 Kotlin 里最常见的写法（{@code @Override fun}、{@code @Composable fun}），
     * 不认注解会漏掉一大批真实符号。
     */
    private static final String ANNOTATION = "@[A-Za-z_]\\w*(?::[A-Za-z_]\\w*)?"
            + "(?:\\([^)\\n]*\\))?\\s+";

    /** 声明前允许出现的修饰符（含 {@code companion} 前缀与任意多个注解）。 */
    private static final String MODIFIER = "(?:(?:" + ANNOTATION + ")|(?:companion|public|"
            + "internal|private|protected|open|abstract|sealed|data|value|inline|annotation|"
            + "expect|actual|external|final|override|lateinit|const|infix|operator|suspend|"
            + "tailrec|noinline|crossinline|reified|vararg|inner|enum)\\s+)*";

    private static final Pattern TYPE_DECL = Pattern.compile(
            "^\\s*" + MODIFIER
                    + "(class|interface|object|enum\\s+class|annotation\\s+class|typealias)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FUN_DECL = Pattern.compile(
            "^\\s*" + MODIFIER
                    + "fun\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?([A-Za-z_][A-Za-z0-9_]*)");
    /** 顶层属性：不做类型推断，只要求声明前没有注释以外的内容、也不含缩进以外的层级信息。 */
    private static final Pattern VAL_DECL = Pattern.compile(
            "^\\s*" + MODIFIER + "(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern CTOR_PROP = Pattern.compile(
            "(?:^|[(,])\\s*(?:(?:private|internal|protected|public)\\s+)?"
                    + "(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");

    /** 一个命中的符号。 */
    public static final class Symbol {
        public final String name;
        public final int kind;
        public final String detail;
        /** 声明所在文件（相对工程根的路径），用于提示来源。 */
        public final String source;

        Symbol(String name, int kind, String detail, String source) {
            this.name = name;
            this.kind = kind;
            this.detail = detail;
            this.source = source;
        }
    }

    private static final KotlinSourceIndex INSTANCE = new KotlinSourceIndex();

    public static KotlinSourceIndex get() {
        return INSTANCE;
    }

    private final Object lock = new Object();

    /** 名称 → 符号（同名保留首次命中，后面的只补来源）。 */
    private Map<String, Symbol> symbols = Collections.emptyMap();

    private long lastScanAt;
    private String lastProjectDir;
    private int lastFileCount;

    /** 是否已经有一次扫描在跑，避免并发重入把同一个工程扫两遍。 */
    private boolean scanning;

    private KotlinSourceIndex() {
    }

    /**
     * 取当前工程内的符号快照。
     *
     * <p>首次调用或距上次<strong>扫描完成</strong>超过 {@link #REFRESH_INTERVAL_MS}
     * 时会尝试重扫；重扫期间沿用旧快照，保证调用方总能立刻拿到结果。
     */
    public List<Symbol> snapshot() {
        return snapshot(currentProjectDir());
    }

    /**
     * 指定工程目录取样（供单测与诊断使用）。
     *
     * <p>锁只用来读写状态，<b>扫盘在锁外</b>执行：一次扫描可能读上千个文件，
     * 占着锁会让 {@code invalidate()}（宿主保存文件时会调）一起卡住。
     * 并发调用时只有第一个真正扫盘，其余直接拿当前快照。
     *
     * @param projectDir 工程根绝对路径；{@code null} 表示拿不到工程信息，
     *                   此时不扫盘，只在 {@value #REFRESH_INTERVAL_MS} 毫秒内沿用旧快照
     */
    public List<Symbol> snapshot(String projectDir) {
        boolean shouldScan;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            boolean projectChanged = !eq(projectDir, lastProjectDir);
            shouldScan = projectDir != null
                    && !scanning
                    && (projectChanged || now - lastScanAt > REFRESH_INTERVAL_MS);
            if (shouldScan) {
                scanning = true;
                lastProjectDir = projectDir;
                if (projectChanged) {
                    // 换了工程：先清空，避免把上一个工程的符号当成当前工程候选。
                    // 宁可暂时没有补全，也不能给出错的结果。
                    symbols = Collections.emptyMap();
                }
            }
            if (!shouldScan) {
                return copyOf(symbols);
            }
        }

        Map<String, Symbol> scanned = Collections.emptyMap();
        List<Symbol> result;
        try {
            Map<String, Symbol> found = scan(projectDir);
            if (found != null) {
                scanned = found;
            }
        } finally {
            synchronized (lock) {
                // 工程根在扫描期间被换掉（甚至被置 null）时，不要把这个旧目录的结果写回去。
                if (eq(projectDir, lastProjectDir)) {
                    symbols = scanned;
                }
                // 时间戳必须在扫描<b>之后</b>记：否则一次超过 REFRESH_INTERVAL_MS
                // 的扫描会让下一次按键立刻又满足「过期」，退化成每按键全盘扫描。
                lastScanAt = System.currentTimeMillis();
                scanning = false;
                result = copyOf(symbols);
            }
        }
        return result;
    }

    private static List<Symbol> copyOf(Map<String, Symbol> source) {
        return source.isEmpty()
                ? Collections.<Symbol>emptyList()
                : new ArrayList<>(source.values());
    }

    /** 当前快照里的符号数量（诊断用）。 */
    public int size() {
        synchronized (lock) {
            return symbols.size();
        }
    }

    /**
     * 丢掉缓存，下次 {@link #snapshot()} 强制重扫，并清空已有符号。
     *
     * <p>用在「工程已关闭 / 目录已被删除」这类场景：此时旧的符号已经没有任何意义。
     */
    public void invalidate() {
        synchronized (lock) {
            lastScanAt = 0L;
            lastProjectDir = null;
            symbols = Collections.emptyMap();
        }
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    private Map<String, Symbol> scan(String projectDir) {
        File root = new File(projectDir);
        if (!root.isDirectory()) {
            return null;
        }
        Map<String, Symbol> found = new LinkedHashMap<>();
        int[] budget = new int[]{0, 0}; // [0]=已解析的 Kotlin 文件数, [1]=已枚举目录数
        try {
            walk(root, root, 0, found, budget);
        } catch (Throwable t) {
            // 扫盘过程中的任何异常都不该让补全失败：已有结果照旧返回。
            return found.isEmpty() ? null : found;
        }
        lastFileCount = budget[0];
        return found;
    }

    /**
     * 递归遍历（深度直接传参，避免每个目录都回溯一遍父链）。
     *
     * <p>{@code budget[0]} 只统计<b>已解析的 Kotlin 文件</b>：如果连图片、依赖包
     * 都算进预算，一个带资源目录的工程很容易把额度吃光而一个 {@code .kt} 都索引不到。
     * 目录另设 {@link #MAX_DIRS} 上限兜底。
     */
    private void walk(File root, File dir, int depth, Map<String, Symbol> out, int[] budget) {
        if (depth > MAX_DEPTH || budget[0] >= MAX_FILES || budget[1] >= MAX_DIRS) {
            return;
        }
        budget[1]++;
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (budget[0] >= MAX_FILES) {
                return;
            }
            if (child.isDirectory()) {
                if (!isIgnoredDir(child.getName())) {
                    walk(root, child, depth + 1, out, budget);
                }
                continue;
            }
            if (!isKotlinFile(child) || child.length() > MAX_FILE_BYTES) {
                continue;
            }
            budget[0]++;
            parseFile(root, child, out);
        }
    }

    private void parseFile(File root, File file, Map<String, Symbol> out) {
        String text;
        try {
            text = readText(file);
        } catch (Throwable t) {
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        String relative = relativePath(root, file);
        String packageName = null;
        Matcher pkg = PACKAGE.matcher(text);
        if (pkg.find()) {
            packageName = pkg.group(1);
        }
        // 先剔除块注释与三引号原始字符串：里面的 `class Foo` 不是声明，
        // 而且它们会跨行，逐行处理看不到边界。
        String[] lines = stripCommentsAndRawStrings(text);
        // 跨行收集「类声明 + 主构造参数」：class A( 与 ) 可能不在同一行，
        // 只在同一行里找 val/var 会漏掉多行主构造的属性。
        StringBuilder constructorSignature = null;
        // 花括号深度：声明分类靠正则，深度只用来判断「属性是不是局部变量」。
        int braceDepth = 0;
        for (String line : lines) {
            String stripped = stripLineComment(line);
            // 已经在收集主构造签名：继续追加，直到括号配平或遇到类体。
            if (constructorSignature != null) {
                int bodyStart = stripped.indexOf('{');
                if (bodyStart >= 0) {
                    // 跨行主构造的收尾行（`) {`）：签名到这里结束，但类体的 `{`
                    // 计入深度必须在收尾后照做——否则类体深度停在 0，
                    // 类里的属性会被 isMemberPosition 误判成局部变量丢掉。
                    constructorSignature.append(' ').append(stripped.substring(0, bodyStart));
                    collectConstructorProperties(out, constructorSignature.toString(),
                            relative, packageName);
                    constructorSignature = null;
                    braceDepth = Math.max(0, braceDepth + braceDelta(stripped));
                    continue;
                }
                constructorSignature.append(' ').append(stripped);
                if (!hasUnclosedParen(constructorSignature)) {
                    collectConstructorProperties(out, constructorSignature.toString(),
                            relative, packageName);
                    constructorSignature = null;
                }
                continue;
            }
            if (stripped.trim().isEmpty()) {
                continue;
            }
            Matcher type = TYPE_DECL.matcher(stripped);
            if (type.find()) {
                add(out, type.group(2), kindOfType(type.group(1)), source(relative, packageName),
                        type.group(1).replaceAll("\\s+", " "));
                // 只看到「类体开始之前」的部分：否则 `class A { val x = 1 }` 这种
                // 单行写法会把类体里的属性当成主构造参数重复收一遍。
                int bodyStart = stripped.indexOf('{');
                String declaration = bodyStart < 0 ? stripped : stripped.substring(0, bodyStart);
                if (bodyStart < 0 && hasUnclosedParen(declaration)) {
                    // 主构造参数列表跨行（class A( 换行 …)），交给后续行继续拼。
                    constructorSignature = new StringBuilder(declaration);
                    continue;
                }
                // 有类体（或签名已完整）：只取签名部分收属性，
                // 并且<b>不能</b>在这里 continue——行尾的 `{` 还要计入花括号深度，
                // 否则类体深度永远是 0，类里的属性会被当成局部变量丢掉。
                collectConstructorProperties(out, declaration, relative, packageName);
            }
            Matcher fun = FUN_DECL.matcher(stripped);
            if (fun.find()) {
                add(out, fun.group(1), CompletionItemKind.Function,
                        source(relative, packageName), "函数");
                continue;
            }
            Matcher val = VAL_DECL.matcher(stripped);
            if (val.find() && isMemberPosition(braceDepth, stripped)) {
                // 只收「顶层属性」与「类体里的属性」：正则没法区分它们和函数体内的
                // 局部变量，但花括号深度可以——局部变量必然位于方法体内（深度 ≥ 2）。
                add(out, val.group(1), CompletionItemKind.Property,
                        source(relative, packageName), "属性");
            }
            braceDepth = Math.max(0, braceDepth + braceDelta(stripped));
        }
    }

    /**
     * 该行是否可能声明「成员属性」而不是函数体内的局部变量。
     *
     * <p>判据是花括号深度：0 = 顶层，1 = 类体或函数体。仅靠深度仍会把函数体的
     * 一级缩进误判成类成员，所以再加一条缩进约束（≤ 1 个缩进层级）。
     */
    private static boolean isMemberPosition(int braceDepth, CharSequence line) {
        if (braceDepth > 1) {
            return false;
        }
        int indent = leadingWhitespace(line);
        // 顶层属性几乎不缩进；类成员通常缩进一级（4 空格或 1 个制表符）。
        return braceDepth == 0 ? indent == 0 : indent <= 4;
    }

    /** 一行里的花括号净变化量（已剔除注释与字符串字面量）。 */
    private static int braceDelta(CharSequence line) {
        String cleaned = stripLiterals(line.toString());
        int delta = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{') {
                delta++;
            } else if (c == '}') {
                delta--;
            }
        }
        return delta;
    }

    /** 去掉字符串/字符字面量（保留引号外的结构字符）。 */
    private static String stripLiterals(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
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
            out.append(c);
        }
        return out.toString();
    }

    /** 从（可能跨行的）构造签名里收 {@code val}/{@code var} 参数为属性。 */
    private void collectConstructorProperties(Map<String, Symbol> out, String signature,
                                              String relative, String packageName) {
        if (signature == null || signature.isEmpty()) {
            return;
        }
        for (Matcher prop = CTOR_PROP.matcher(signature); prop.find(); ) {
            add(out, prop.group(1), CompletionItemKind.Property,
                    source(relative, packageName), "属性");
        }
    }

    /**
     * 声明里是否存在「还没闭合的左圆括号」——即主构造参数列表是否跨行。
     *
     * <p>注意不能反过来用「括号是否配平」来判断：{@code typealias} / {@code object} /
     * 没有参数列表的 {@code class} 根本不含括号，那种写法会被误判成「还没读完」，
     * 于是把后面几行都吞进签名里。所以判据必须是「见过 {@code (} 且计数大于 0」。
     *
     * <p>字符串字面量里的括号会被跳过。
     */
    private static boolean hasUnclosedParen(CharSequence text) {
        if (text == null) {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == quote && (i == 0 || text.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
        }
        return depth > 0;
    }

    private void add(Map<String, Symbol> out, String name, int kind, String source, String detail) {
        if (name == null || name.isEmpty() || out.containsKey(name)) {
            return;
        }
        out.put(name, new Symbol(name, kind, detail + " · " + source, source));
    }

    private static int kindOfType(String keyword) {
        String normalized = keyword.replaceAll("\\s+", " ");
        if (normalized.startsWith("interface")) {
            return CompletionItemKind.Interface;
        }
        if (normalized.startsWith("enum")) {
            return CompletionItemKind.Enum;
        }
        if (normalized.startsWith("object")) {
            return CompletionItemKind.Module;
        }
        if (normalized.startsWith("typealias")) {
            return CompletionItemKind.Class;
        }
        return CompletionItemKind.Class;
    }

    // ------------------------------------------------------------------
    // 当前文档词
    // ------------------------------------------------------------------

    /**
     * 从一段源码里抽取可用于补全的标识符（去掉已完成化的 Kotlin 关键字）。
     *
     * <p>用于「同一文件里刚写过的变量名/函数名」这类就近补全。
     */
    public static Set<String> wordsOf(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> words = new LinkedHashSet<>();
        // 注释与字符串里的词不该成为补全候选（否则会把「文档里提到的词」也塞进来）。
        StringBuilder cleaned = new StringBuilder(content.length());
        for (String line : stripCommentsAndRawStrings(content)) {
            cleaned.append(stripLineComment(line)).append('\n');
        }
        Matcher matcher = WORD.matcher(cleaned);
        while (matcher.find() && words.size() < 600) {
            String word = matcher.group();
            if (KotlinSnippets.isKeyword(word)) {
                continue;
            }
            words.add(word);
        }
        return words;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String readText(File file) throws Exception {
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        try {
            StringBuilder builder = new StringBuilder((int) Math.min(file.length(), 64 * 1024));
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 行首空白字符个数（制表符按 1 计）。 */
    private static int leadingWhitespace(CharSequence line) {
        int count = 0;
        while (count < line.length()) {
            char c = line.charAt(count);
            if (c != ' ' && c != '\t') {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 剔除块注释与三引号原始字符串（两者都可能跨行）。
     *
     * <p>逐行的行注释在 {@link #stripLineComment} 里处理；这里只处理跨行结构。
     * 行数保持不变，{@code line} 的替换只用于顶掉注释里可能出现的声明关键字。
     */
    private static String[] stripCommentsAndRawStrings(String text) {
        String[] lines = text.split("\n", -1);
        boolean inBlockComment = false;
        boolean inRawString = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            StringBuilder out = new StringBuilder(line.length());
            int index = 0;
            while (index < line.length()) {
                if (inBlockComment) {
                    int end = line.indexOf("*/", index);
                    if (end < 0) {
                        index = line.length();
                    } else {
                        inBlockComment = false;
                        index = end + 2;
                    }
                    continue;
                }
                if (inRawString) {
                    int end = line.indexOf("\"\"\"", index);
                    if (end < 0) {
                        index = line.length();
                    } else {
                        inRawString = false;
                        index = end + 3;
                    }
                    continue;
                }
                int block = line.indexOf("/*", index);
                int raw = line.indexOf("\"\"\"", index);
                int lineComment = line.indexOf("//", index);
                int next = -1;
                char kind = 0;
                if (block >= 0 && (next < 0 || block < next)) {
                    next = block;
                    kind = 'b';
                }
                if (raw >= 0 && (next < 0 || raw < next)) {
                    next = raw;
                    kind = 'r';
                }
                if (lineComment >= 0 && (next < 0 || lineComment < next)) {
                    next = lineComment;
                    kind = 'l';
                }
                if (next < 0) {
                    out.append(line, index, line.length());
                    break;
                }
                out.append(line, index, next);
                if (kind == 'l') {
                    break;
                }
                if (kind == 'b') {
                    inBlockComment = true;
                    index = next + 2;
                } else {
                    inRawString = true;
                    index = next + 3;
                }
            }
            lines[i] = out.toString();
        }
        return lines;
    }

    /** 去掉行内的 {@code //} 注释，避免注释里的关键字被当成声明。 */
    private static String stripLineComment(String line) {
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"' && !inChar && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == '\'' && !inString && (i == 0 || line.charAt(i - 1) != '\\')) {
                inChar = !inChar;
            } else if (!inString && !inChar && c == '/' && line.charAt(i + 1) == '/') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static boolean isKotlinFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".kt") || name.endsWith(".kts");
    }

    private static boolean isIgnoredDir(String name) {
        return "build".equals(name) || ".git".equals(name) || ".gradle".equals(name)
                || ".idea".equals(name) || "node_modules".equals(name);
    }


    private static String relativePath(File root, File file) {
        try {
            String rootPath = root.getAbsolutePath();
            String filePath = file.getAbsolutePath();
            if (filePath.startsWith(rootPath)) {
                String relative = filePath.substring(rootPath.length());
                return relative.startsWith(File.separator) ? relative.substring(1) : relative;
            }
            return file.getName();
        } catch (Throwable t) {
            return file.getName();
        }
    }

    private static String source(String relative, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return relative;
        }
        return packageName + " (" + relative + ")";
    }

    private static boolean eq(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** 当前工程根目录；没有打开工程时返回 null。 */
    private static String currentProjectDir() {
        try {
            Project project = Platform.getProject();
            File dir = project == null ? null : project.getProjectDir();
            return dir == null ? null : dir.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供诊断输出使用：已扫描文件数。 */
    public int lastFileCount() {
        synchronized (lock) {
            return lastFileCount;
        }
    }
}
