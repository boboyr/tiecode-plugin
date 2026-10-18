package com.tiecode.ktcplugin.lang.kt;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Kotlin 关键字与代码片段目录。
 *
 * <p>关键字用于<b>语法级提示</b>，片段用于把常用结构一次补全（body 使用
 * LSP Snippet 语法：{@code ${1:占位}} / {@code $0} 为最终光标位置）。
 */
public final class KotlinSnippets {

    /** Kotlin 硬关键字与修饰符，同时也是「不该作为普通标识符补全」的词表。 */
    private static final Set<String> KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return", "super",
            "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
            "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally",
            "get", "import", "init", "param", "property", "receiver", "set", "setparam", "where",
            "abstract", "actual", "annotation", "companion", "const", "crossinline", "data",
            "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
            "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected",
            "public", "reified", "sealed", "suspend", "tailrec", "vararg", "value"
    )));

    /** 关键字的补全候选：{名称, 说明}。 */
    private static final String[][] KEYWORD_ROWS = {
            {"val", "只读变量声明"},
            {"var", "可变变量声明"},
            {"fun", "函数声明"},
            {"class", "类声明"},
            {"interface", "接口声明"},
            {"object", "对象声明 / 单例"},
            {"companion", "伴生对象"},
            {"data", "数据类修饰符"},
            {"sealed", "密封类修饰符"},
            {"enum", "枚举类修饰符"},
            {"annotation", "注解类修饰符"},
            {"open", "可被继承 / 重写"},
            {"override", "重写父类成员"},
            {"abstract", "抽象成员"},
            {"private", "私有可见性"},
            {"internal", "模块内可见性"},
            {"protected", "子类可见性"},
            {"public", "公开可见性"},
            {"lateinit", "延迟初始化属性"},
            {"const", "编译期常量"},
            {"suspend", "挂起函数"},
            {"inline", "内联函数"},
            {"noinline", "禁止内联的 lambda 参数"},
            {"crossinline", "禁止非局部返回的 lambda 参数"},
            {"reified", "内联函数中具体化的类型参数"},
            {"vararg", "可变参数"},
            {"operator", "运算符重载"},
            {"infix", "中缀函数"},
            {"tailrec", "尾递归优化"},
            {"external", "外部实现（JNI）"},
            {"typealias", "类型别名"},
            {"if", "条件分支"},
            {"else", "否则分支"},
            {"when", "多分支表达式"},
            {"for", "遍历循环"},
            {"while", "条件循环"},
            {"do", "至少执行一次的循环"},
            {"break", "跳出循环"},
            {"continue", "跳过本次循环"},
            {"return", "返回"},
            {"try", "异常捕获"},
            {"catch", "捕获异常"},
            {"finally", "收尾块"},
            {"throw", "抛出异常"},
            {"in", "包含判断 / 区间遍历"},
            {"is", "类型判断（智能转换）"},
            {"as", "类型转换"},
            {"by", "委托"},
            {"import", "导入"},
            {"package", "包声明"},
            {"this", "当前接收者"},
            {"super", "父类接收者"},
            {"null", "空值"},
            {"true", "布尔真"},
            {"false", "布尔假"},
            {"init", "初始化块"},
            {"constructor", "构造函数"},
            {"get", "属性读访问器"},
            {"set", "属性写访问器"},
    };

    /** 代码片段：{标签, 说明, 片段正文}。 */
    private static final String[][] SNIPPET_ROWS = {
            {"fun", "函数声明",
                    "fun ${1:name}(${2}): ${3:Unit} {\n\t$0\n}"},
            {"main", "程序入口",
                    "fun main(args: Array<String>) {\n\t$0\n}"},
            {"class", "类声明",
                    "class ${1:Name} {\n\t$0\n}"},
            {"class主构造", "带主构造参数的类",
                    "class ${1:Name}(${2:val value: ${3:String}}) {\n\t$0\n}"},
            {"dataclass", "数据类",
                    "data class ${1:Name}(\n\tval ${2:field}: ${3:String}\n)"},
            {"enumclass", "枚举类",
                    "enum class ${1:Name} {\n\t${2:VALUE}\n}"},
            {"sealedclass", "密封类",
                    "sealed class ${1:Name} {\n\t$0\n}"},
            {"interface", "接口声明",
                    "interface ${1:Name} {\n\tfun ${2:method}()\n}"},
            {"object", "单例对象",
                    "object ${1:Name} {\n\t$0\n}"},
            {"companion", "伴生对象",
                    "companion object {\n\t$0\n}"},
            {"if", "条件分支",
                    "if (${1:condition}) {\n\t$0\n}"},
            {"ifelse", "条件分支（否则）",
                    "if (${1:condition}) {\n\t$2\n} else {\n\t$0\n}"},
            {"when", "多分支表达式",
                    "when (${1:value}) {\n\t${2:case} -> $3\n\telse -> $0\n}"},
            {"whenval", "when 赋值",
                    "val ${1:result} = when (${2:value}) {\n\t${3:case} -> $4\n\telse -> $0\n}"},
            {"for", "区间遍历",
                    "for (${1:i} in ${2:0 until 10}) {\n\t$0\n}"},
            {"foreach", "集合遍历",
                    "${1:list}.forEach { ${2:item} ->\n\t$0\n}"},
            {"while", "条件循环",
                    "while (${1:condition}) {\n\t$0\n}"},
            {"dowhile", "至少执行一次的循环",
                    "do {\n\t$0\n} while (${1:condition})"},
            {"try", "异常捕获",
                    "try {\n\t$0\n} catch (${1:e}: ${2:Exception}) {\n\t$3\n}"},
            {"tryfinally", "异常捕获并收尾",
                    "try {\n\t$1\n} catch (${1:e}: Exception) {\n\t$2\n} finally {\n\t$0\n}"},
            {"lambda", "Lambda 表达式",
                    "{ ${1:it} ->\n\t$0\n}"},
            {"let", "let 作用域",
                    "${1:value}?.let { ${2:it} ->\n\t$0\n}"},
            {"apply", "apply 配置",
                    "${1:value}.apply {\n\t$0\n}"},
            {"also", "also 附加操作",
                    "${1:value}.also { ${2:it} ->\n\t$0\n}"},
            {"run", "run 作用域",
                    "run {\n\t$0\n}"},
            {"with", "with 作用域",
                    "with(${1:receiver}) {\n\t$0\n}"},
            {"lazy", "惰性属性",
                    "val ${1:name}: ${2:Type} by lazy {\n\t$0\n}"},
            {"listof", "创建列表",
                    "val ${1:list} = listOf(${2})"},
            {"mapof", "创建键值表",
                    "val ${1:map} = mapOf(\n\t${2:key} to ${3:value}\n)"},
            {"mutablelistof", "创建可变列表",
                    "val ${1:list} = mutableListOf<${2:Type}>()"},
            {"isNotEmpty", "空判断",
                    "if (!${1:list}.isNullOrEmpty()) {\n\t$0\n}"},
            {"whennull", "空安全取值",
                    "val ${1:name} = ${2:value} ?: return"},
            {"test", "单元测试方法",
                    "fun ${1:testSomething}() {\n\t$0\n}"},
            {"launch", "协程启动",
                    "CoroutineScope(Dispatchers.IO).launch {\n\t$0\n}"},
            {"suspendfun", "挂起函数",
                    "suspend fun ${1:name}(): ${2:Unit} {\n\t$0\n}"},
            {"companionfun", "伴生对象工厂方法",
                    "companion object {\n\tfun ${1:create}(): ${2:Name} {\n\t\t$0\n\t}\n}"},
    };

    public static boolean isKeyword(String word) {
        return word != null && KEYWORDS.contains(word);
    }

    public static String[][] keywords() {
        return KEYWORD_ROWS.clone();
    }

    public static String[][] snippets() {
        return SNIPPET_ROWS.clone();
    }

    private KotlinSnippets() {
    }
}
