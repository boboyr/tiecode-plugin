package com.tiecode.ktcplugin.lang.kt;

import com.rapid.api.lang.engine.completion.CompletionItemKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kotlin 标准库 / 语言内置符号目录。
 *
 * <p>数据是<b>离线内置</b>的：不联网、不依赖 kotlinc，保证断网和冷启动时补全
 * 依然可用。只收录日常写 Kotlin 代码最常用的部分——函数、类、注解与内置类型，
 * 而不是完整标准库（完整版会被 kotlinc 元数据解析替代，见工程说明）。
 *
 * <p>每条记录：{@code {名称, 签名, 一句话说明}}；带泛型的类型只把名字作为补全文本，
 * 泛型信息放在说明里（原样插入 {@code Array<T>} 会直接产生语法错误）。
 * 说明为空时使用 {@code detail} 作为提示。
 */
public final class KotlinStdlib {

    /** 内置类型与常用类。 */
    private static final String[][] TYPES = {
            // ---- 基础类型 ----
            {"Int", "Int", "32 位整数"},
            {"Long", "Long", "64 位整数（后缀 L）"},
            {"Short", "Short", "16 位整数"},
            {"Byte", "Byte", "8 位整数"},
            {"Double", "Double", "64 位浮点数"},
            {"Float", "Float", "32 位浮点数（后缀 f）"},
            {"Boolean", "Boolean", "布尔值 true / false"},
            {"Char", "Char", "单个 UTF-16 字符"},
            {"String", "String", "不可变字符串"},
            {"CharSequence", "CharSequence", "字符序列接口"},
            {"Any", "Any", "所有非空类型的根类型"},
            {"Nothing", "Nothing", "没有值的类型（抛异常 / 不返回）"},
            {"Unit", "Unit", "无返回值，等价于 Java 的 void"},
            {"Number", "Number", "所有数值类型的父类型"},
            {"Array", "Array<T>", "定长数组"},
            {"IntArray", "IntArray", "基本类型 int 数组"},
            {"LongArray", "LongArray", "基本类型 long 数组"},
            {"DoubleArray", "DoubleArray", "基本类型 double 数组"},
            {"FloatArray", "FloatArray", "基本类型 float 数组"},
            {"BooleanArray", "BooleanArray", "基本类型 boolean 数组"},
            {"ByteArray", "ByteArray", "字节数组"},
            {"CharArray", "CharArray", "字符数组"},
            {"ShortArray", "ShortArray", "基本类型 short 数组"},
            {"Pair", "Pair<A, B>", "二元组，可用 first / second 访问"},
            {"Triple", "Triple<A, B, C>", "三元组，可用 first / second / third 访问"},
            {"UInt", "UInt", "无符号 32 位整数"},
            {"ULong", "ULong", "无符号 64 位整数"},

            // ---- 集合接口 ----
            {"Iterable", "Iterable<T>", "可迭代，for 循环的基础"},
            {"Collection", "Collection<T>", "只读集合"},
            {"List", "List<T>", "只读列表，支持下标访问"},
            {"MutableList", "MutableList<T>", "可变列表"},
            {"Set", "Set<T>", "只读集合，元素不重复"},
            {"MutableSet", "MutableSet<T>", "可变集合"},
            {"Map", "Map<K, V>", "只读键值表"},
            {"MutableMap", "MutableMap<K, V>", "可变键值表"},
            {"Iterator", "Iterator<T>", "迭代器"},
            {"Sequence", "Sequence<T>", "惰性序列，适合大数据量链式操作"},
            {"Map.Entry", "Map.Entry<K, V>", "键值表条目"},
            {"IndexedValue", "IndexedValue<T>", "withIndex() 的结果，含 index 与 value"},
            {"ClosedRange", "ClosedRange<T>", "闭区间"},
            {"IntRange", "IntRange", "整数区间，如 1..10"},
            {"CharRange", "CharRange", "字符区间"},
            {"LongRange", "LongRange", "长整数区间"},
            {"Progression", "Progression", "等差数列，如 10 downTo 1 step 2"},

            // ---- 通用容器 / 函数类型 ----
            {"Result", "Result<T>", "可成功可失败的返回值容器"},
            {"Lazy", "Lazy<T>", "by lazy 委托的返回类型"},
            {"Regex", "Regex", "正则表达式"},
            {"MatchResult", "MatchResult", "正则匹配结果"},
            {"MatchGroupCollection", "MatchGroupCollection", "正则分组集合"},
            {"Comparator", "Comparator<T>", "比较器"},
            {"Comparable", "Comparable<T>", "可比较"},
            {"Function", "Function<R>", "函数类型"},
            {"Runnable", "Runnable", "无参无返回值的可执行体"},
            {"StringBuilder", "StringBuilder", "可变字符串构建器"},

            // ---- 常用工具类 ----
            {"System", "System", "系统级工具（currentTimeMillis / exit / getenv）"},
            {"Thread", "Thread", "线程"},
            {"Exception", "Exception", "受检异常基类"},
            {"Error", "Error", "错误基类"},
            {"RuntimeException", "RuntimeException", "运行时异常基类"},
            {"IllegalArgumentException", "IllegalArgumentException", "参数非法"},
            {"IllegalStateException", "IllegalStateException", "状态非法"},
            {"IndexOutOfBoundsException", "IndexOutOfBoundsException", "下标越界"},
            {"NullPointerException", "NullPointerException", "空指针"},
            {"UnsupportedOperationException", "UnsupportedOperationException", "不支持的操作"},
            {"NoSuchElementException", "NoSuchElementException", "元素不存在"},
            {"NumberFormatException", "NumberFormatException", "数字格式错误"},
            {"Throwable", "Throwable", "所有可抛出对象的根类型"},

            // ---- 注解 ----
            {"JvmStatic", "JvmStatic", "把伴生对象成员暴露为 Java 静态成员"},
            {"JvmField", "JvmField", "把属性暴露为 Java 字段"},
            {"JvmName", "JvmName", "指定生成的 JVM 名称（文件级用 @file:JvmName）"},
            {"JvmOverloads", "JvmOverloads", "为带默认值的参数生成重载"},
            {"JvmInline", "JvmInline", "内联值类"},
            {"JvmSuppressWildcards", "JvmSuppressWildcards", "抑制通配符生成"},
            {"Suppress", "Suppress", "压制指定警告"},
            {"Deprecated", "Deprecated", "标记已废弃"},
            {"Synchronized", "Synchronized", "方法加锁"},
            {"Volatile", "Volatile", "字段可见性"},
            {"Transient", "Transient", "序列化时忽略"},
            {"Strictfp", "Strictfp", "严格浮点语义"},
            {"Throws", "Throws", "声明受检异常"},
            {"OptIn", "OptIn", "显式声明使用实验性 API"},
            {"RequiresOptIn", "RequiresOptIn", "声明实验性 API"},
            {"ExperimentalStdlibApi", "ExperimentalStdlibApi", "标准库实验性 API 标记"},
    };

    /** 顶层函数。 */
    private static final String[][] FUNCTIONS = {
            // ---- 输出与输入 ----
            {"println", "println(message: Any?)", "打印一行"},
            {"print", "print(message: Any?)", "打印不换行"},
            {"readLine", "readLine(): String?", "读取一行输入"},
            {"readln", "readln(): String", "读取一行输入（非空，无输入抛异常）"},
            {"readlnOrNull", "readlnOrNull(): String?", "读取一行输入，无输入返回 null"},
            {"repeat", "repeat(times: Int) { … }", "重复执行 n 次"},
            {"TODO", "TODO(): Nothing", "标记未实现，运行到此处抛异常"},
            {"error", "error(message: Any): Nothing", "抛出 IllegalStateException"},
            {"check", "check(value: Boolean)", "断言为真，否则抛 IllegalStateException"},
            {"require", "require(value: Boolean)", "断言为真，否则抛 IllegalArgumentException"},

            // ---- 集合创建 ----
            {"listOf", "listOf(vararg elements: T): List<T>", "创建只读列表"},
            {"listOfNotNull", "listOfNotNull(vararg elements: T?): List<T>", "创建列表并剔除 null"},
            {"mutableListOf", "mutableListOf(vararg elements: T): MutableList<T>", "创建可变列表"},
            {"arrayListOf", "arrayListOf(vararg elements: T): ArrayList<T>", "创建 ArrayList"},
            {"setOf", "setOf(vararg elements: T): Set<T>", "创建只读集合"},
            {"mutableSetOf", "mutableSetOf(vararg elements: T): MutableSet<T>", "创建可变集合"},
            {"hashSetOf", "hashSetOf(vararg elements: T): HashSet<T>", "创建 HashSet"},
            {"mapOf", "mapOf(vararg pairs: Pair<K, V>): Map<K, V>", "创建只读键值表"},
            {"mutableMapOf", "mutableMapOf(vararg pairs: Pair<K, V>): MutableMap<K, V>", "创建可变键值表"},
            {"hashMapOf", "hashMapOf(vararg pairs: Pair<K, V>): HashMap<K, V>", "创建 HashMap"},
            {"linkedMapOf", "linkedMapOf(vararg pairs: Pair<K, V>): LinkedHashMap<K, V>", "创建有序 HashMap"},
            {"sortedMapOf", "sortedMapOf(vararg pairs: Pair<K, V>): SortedMap<K, V>", "创建有序键值表"},
            {"emptyList", "emptyList(): List<T>", "空列表"},
            {"emptySet", "emptySet(): Set<T>", "空集合"},
            {"emptyMap", "emptyMap(): Map<K, V>", "空键值表"},
            {"arrayOf", "arrayOf(vararg elements: T): Array<T>", "创建数组"},
            {"arrayOfNulls", "arrayOfNulls(size: Int): Array<T?>", "创建元素全为 null 的数组"},
            {"emptyArray", "emptyArray(): Array<T>", "空数组"},
            {"intArrayOf", "intArrayOf(vararg elements: Int): IntArray", "创建 Int 数组"},
            {"longArrayOf", "longArrayOf(vararg elements: Long): LongArray", "创建 Long 数组"},
            {"doubleArrayOf", "doubleArrayOf(vararg elements: Double): DoubleArray", "创建 Double 数组"},
            {"floatArrayOf", "floatArrayOf(vararg elements: Float): FloatArray", "创建 Float 数组"},
            {"booleanArrayOf", "booleanArrayOf(vararg elements: Boolean): BooleanArray", "创建 Boolean 数组"},
            {"byteArrayOf", "byteArrayOf(vararg elements: Byte): ByteArray", "创建 Byte 数组"},
            {"charArrayOf", "charArrayOf(vararg elements: Char): CharArray", "创建 Char 数组"},
            {"List", "List(size: Int) { index -> … }", "用下标工厂函数创建列表"},
            {"MutableList", "MutableList(size: Int) { index -> … }", "用下标工厂函数创建可变列表"},
            {"sequenceOf", "sequenceOf(vararg elements: T): Sequence<T>", "创建序列"},
            {"generateSequence", "generateSequence(seed) { next -> … }", "生成链式序列"},

            // ---- 区间与数学 ----
            {"minOf", "minOf(a: T, b: T): T", "取较小值"},
            {"maxOf", "maxOf(a: T, b: T): T", "取较大值"},
            {"minOfOrNull", "minOfOrNull(a: T, b: T): T?", "取较小值，全空返回 null"},
            {"maxOfOrNull", "maxOfOrNull(a: T, b: T): T?", "取较大值，全空返回 null"},
            {"coerceIn", "coerceIn(minimumValue, maximumValue)", "把值限制到区间内"},
            {"coerceAtLeast", "coerceAtLeast(minimumValue)", "值不小于给定值"},
            {"coerceAtMost", "coerceAtMost(maximumValue)", "值不大于给定值"},
            {"abs", "abs(x: Int): Int", "绝对值"},
            {"sqrt", "sqrt(x: Double): Double", "平方根"},
            {"pow", "pow(x: Double): Double", "幂"},
            {"roundToInt", "roundToInt(): Int", "四舍五入到整数"},
            {"roundToLong", "roundToLong(): Long", "四舍五入到长整数"},
            {"floor", "floor(x: Double): Double", "向下取整"},
            {"ceil", "ceil(x: Double): Double", "向上取整"},

            // ---- 类型转换 ----
            {"toInt", "toInt(): Int", "转换为 Int"},
            {"toIntOrNull", "toIntOrNull(): Int?", "转换为 Int，失败返回 null"},
            {"toLong", "toLong(): Long", "转换为 Long"},
            {"toDouble", "toDouble(): Double", "转换为 Double"},
            {"toFloat", "toFloat(): Float", "转换为 Float"},
            {"toBoolean", "toBoolean(): Boolean", "转换为 Boolean"},
            {"toString", "toString(): String", "转换为字符串"},
            {"toList", "toList(): List<T>", "转换为列表"},
            {"toMutableList", "toMutableList(): MutableList<T>", "转换为可变列表"},
            {"toSet", "toSet(): Set<T>", "转换为集合"},
            {"toMap", "toMap(): Map<K, V>", "转换为键值表"},
            {"toTypedArray", "toTypedArray(): Array<T>", "转换为对象数组"},
            {"joinToString", "joinToString(separator, prefix, postfix)", "拼接集合为字符串"},
            {"joinTo", "joinTo(buffer: Appendable, …)", "把集合拼接到缓冲"},
            {"asSequence", "asSequence(): Sequence<T>", "转换为惰性序列"},

            // ---- 集合操作 ----
            {"map", "map { it -> … }: List<R>", "逐元素映射"},
            {"mapNotNull", "mapNotNull { it -> … }: List<R>", "映射并剔除 null"},
            {"mapIndexed", "mapIndexed { index, it -> … }", "带下标映射"},
            {"filter", "filter { it -> … }: List<T>", "按条件筛选"},
            {"filterNot", "filterNot { it -> … }: List<T>", "取条件不成立的元素"},
            {"filterNotNull", "filterNotNull(): List<T>", "剔除 null"},
            {"forEach", "forEach { it -> … }", "遍历每个元素"},
            {"forEachIndexed", "forEachIndexed { index, it -> … }", "带下标遍历"},
            {"flatMap", "flatMap { it -> … }: List<R>", "映射后展平"},
            {"flatten", "flatten(): List<T>", "展平嵌套集合"},
            {"associate", "associate { it -> key to value }", "构造 Map"},
            {"associateBy", "associateBy { it -> key }", "按 key 构造 Map"},
            {"groupBy", "groupBy { it -> key }: Map<K, List<T>>", "按键分组"},
            {"partition", "partition { it -> … }: Pair<List<T>, List<T>>", "按条件分成两组"},
            {"zip", "zip(other: Iterable<R>): List<Pair<T, R>>", "成对合并两个集合"},
            {"unzip", "unzip(): Pair<List<A>, List<B>>", "拆分 Pair 集合"},
            {"sorted", "sorted(): List<T>", "升序排序"},
            {"sortedBy", "sortedBy { it -> key }", "按键升序排序"},
            {"sortedDescending", "sortedDescending(): List<T>", "降序排序"},
            {"sortedWith", "sortedWith(comparator: Comparator<T>)", "按比较器排序"},
            {"reversed", "reversed(): List<T>", "反转"},
            {"distinct", "distinct(): List<T>", "去重"},
            {"distinctBy", "distinctBy { it -> key }", "按键去重"},
            {"find", "find { it -> … }: T?", "查找首个匹配元素"},
            {"first", "first(): T", "首个元素，空集合抛异常"},
            {"firstOrNull", "firstOrNull([条件]): T?", "首个（匹配的）元素，空集合或找不到返回 null"},
            {"last", "last(): T", "末个元素"},
            {"lastOrNull", "lastOrNull(): T?", "末个元素，空集合返回 null"},
            {"single", "single { it -> … }: T", "唯一匹配元素"},
            {"singleOrNull", "singleOrNull { it -> … }: T?", "唯一匹配元素，否则 null"},
            {"any", "any { it -> … }: Boolean", "是否存在匹配元素"},
            {"all", "all { it -> … }: Boolean", "是否全部匹配"},
            {"none", "none { it -> … }: Boolean", "是否都不匹配"},
            {"count", "count { it -> … }: Int", "匹配元素个数"},
            {"sumOf", "sumOf { it -> number }", "按值求和"},
            {"sum", "sum(): Int", "求和"},
            {"average", "average(): Double", "平均值"},
            {"minOrNull", "minOrNull(): T?", "最小值，空集合返回 null"},
            {"maxOrNull", "maxOrNull(): T?", "最大值，空集合返回 null"},
            {"minByOrNull", "minByOrNull { it -> key }", "按键取最小值"},
            {"maxByOrNull", "maxByOrNull { it -> key }", "按键取最大值"},
            {"reduce", "reduce { acc, it -> … }", "折叠，空集合抛异常"},
            {"fold", "fold(initial) { acc, it -> … }", "带初值折叠"},
            {"take", "take(n: Int): List<T>", "取前 n 个"},
            {"takeLast", "takeLast(n: Int): List<T>", "取后 n 个"},
            {"takeWhile", "takeWhile { it -> … }", "取满足条件的前缀"},
            {"drop", "drop(n: Int): List<T>", "丢弃前 n 个"},
            {"dropLast", "dropLast(n: Int): List<T>", "丢弃后 n 个"},
            {"dropWhile", "dropWhile { it -> … }", "丢弃满足条件的前缀"},
            {"chunked", "chunked(size: Int): List<List<T>>", "按固定长度切片"},
            {"windowed", "windowed(size: Int): List<List<T>>", "滑动窗口"},
            {"slice", "slice(indices: IntRange): List<T>", "按下标区间截取"},
            {"subList", "subList(fromIndex, toIndex): List<T>", "按下标截取"},
            {"indexOf", "indexOf(element: T): Int", "元素下标，找不到返回 -1"},
            {"indexOfFirst", "indexOfFirst { it -> … }: Int", "首个匹配元素下标"},
            {"lastIndexOf", "lastIndexOf(element: T): Int", "末个元素下标"},
            {"contains", "contains(element: T): Boolean", "是否包含元素"},
            {"containsAll", "containsAll(elements: Collection<T>): Boolean", "是否包含全部"},
            {"isEmpty", "isEmpty(): Boolean", "是否为空"},
            {"isNotEmpty", "isNotEmpty(): Boolean", "是否非空"},
            {"add", "add(element: T): Boolean", "追加元素"},
            {"addAll", "addAll(elements: Collection<T>): Boolean", "追加全部元素"},
            {"remove", "remove(element: T): Boolean", "移除元素"},
            {"removeAt", "removeAt(index: Int): T", "按下标移除"},
            {"removeAll", "removeAll(predicate) ", "按条件批量移除"},
            {"clear", "clear()", "清空"},
            {"put", "put(key: K, value: V): V?", "写入键值并返回旧值"},
            {"putAll", "putAll(from: Map<K, V>)", "批量写入"},
            {"getOrDefault", "getOrDefault(key: K, defaultValue: V): V", "取键值，缺失返回默认值"},
            {"getOrPut", "getOrPut(key: K) { defaultValue }", "取键值，缺失则计算并写入"},
            {"getOrElse", "getOrElse(index: Int) { defaultValue }", "取元素，越界时计算默认值"},
            {"getOrNull", "getOrNull(index: Int): T?", "取元素，越界返回 null"},
            {"elementAtOrNull", "elementAtOrNull(index: Int): T?", "按下标取元素，越界返回 null"},

            // ---- 字符串 ----
            {"buildString", "buildString { … }: String", "用 StringBuilder 构建字符串"},
            {"trim", "trim(): String", "去除首尾空白"},
            {"trimIndent", "trimIndent(): String", "去掉多行字符串公共缩进"},
            {"trimMargin", "trimMargin(marginPrefix: String)", "按边界符去缩进"},
            {"split", "split(delimiter: String): List<String>", "按分隔符切分"},
            {"replace", "replace(oldValue: String, newValue: String)", "替换"},
            {"replaceFirst", "replaceFirst(oldValue, newValue)", "替换首个匹配"},
            {"startsWith", "startsWith(prefix: String): Boolean", "是否以指定前缀开头"},
            {"endsWith", "endsWith(suffix: String): Boolean", "是否以指定后缀结尾"},
            {"substring", "substring(startIndex: Int, endIndex: Int)", "截取子串"},
            {"substringBefore", "substringBefore(delimiter: Char)", "取分隔符之前的部分"},
            {"substringAfter", "substringAfter(delimiter: Char)", "取分隔符之后的部分"},
            {"padStart", "padStart(length: Int, padChar: Char)", "左侧补齐"},
            {"padEnd", "padEnd(length: Int, padChar: Char)", "右侧补齐"},
            {"uppercase", "uppercase(): String", "转大写"},
            {"lowercase", "lowercase(): String", "转小写"},
            {"capitalize", "capitalize(): String", "首字母大写"},
            {"isBlank", "isBlank(): Boolean", "是否空白"},
            {"isNotBlank", "isNotBlank(): Boolean", "是否非空白"},
            {"toRegex", "toRegex(): Regex", "转换为正则"},
            {"format", "format(vararg args: Any?): String", "格式化字符串"},

            // ---- 空安全与作用域 ----
            {"let", "let { it -> … }", "把接收者作为 it 传入，返回 lambda 结果"},
            {"run", "run { … }", "在当前作用域执行，返回 lambda 结果"},
            {"with", "with(receiver) { … }", "把接收者作为 this 执行"},
            {"apply", "apply { … }", "配置接收者并返回它"},
            {"also", "also { it -> … }", "附加操作并返回接收者"},

            // ---- 其它 ----
            {"lazy", "lazy { … }: Lazy<T>", "惰性初始化委托"},
            {"synchronized", "synchronized(lock) { … }", "同步块"},
            {"measureTimeMillis", "measureTimeMillis { … }: Long", "统计代码块耗时"},
            {"measureNanoTime", "measureNanoTime { … }: Long", "统计代码块耗时（纳秒）"},
            {"identityHashCode", "identityHashCode(obj: Any?): Int", "对象身份哈希"},
            {"printStackTrace", "printStackTrace()", "打印异常堆栈"},
    };

    /** 一次性构建好的补全候选。 */
    public static final class Entry {
        public final String label;
        public final int kind;
        public final String detail;
        public final String documentation;

        Entry(String label, int kind, String detail) {
            this.label = label;
            this.kind = kind;
            this.detail = detail;
            this.documentation = detail;
        }
    }

    private static final List<Entry> ALL;

    static {
        List<Entry> entries = new ArrayList<>(TYPES.length + FUNCTIONS.length);
        for (String[] row : TYPES) {
            entries.add(new Entry(row[0], CompletionItemKind.Class, row[1] + " · " + row[2]));
        }
        for (String[] row : FUNCTIONS) {
            entries.add(new Entry(row[0], CompletionItemKind.Function, row[1] + " · " + row[2]));
        }
        ALL = Collections.unmodifiableList(entries);
    }

    public static List<Entry> all() {
        return ALL;
    }

    private KotlinStdlib() {
    }
}
