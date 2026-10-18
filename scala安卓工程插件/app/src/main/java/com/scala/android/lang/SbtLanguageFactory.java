package com.scala.android.lang;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

/**
 * 让 <b>{@code *.sbt}</b>（{@code build.sbt}、{@code project/plugins.sbt}…）也走 Scala 语言。
 *
 * <h3>为什么必须单独一个工厂</h3>
 * 宿主的语言是按<b>扩展名</b>找工厂的：
 * <pre>
 * DefaultLanguageManager.findLanguageFactory(fileType)   // 遍历工厂，比对 getAssociateFileType()
 * DefaultLanguageManager.getEngineByExtension(ext)       // 工厂 → languageId → 引擎
 * </pre>
 * {@link ScalaLanguageFactory} 只认领了 {@code scala}，而 {@code build.sbt} 的扩展名是
 * {@code sbt} —— 于是它既找不到语言（没高亮）也找不到引擎（没补全）。
 *
 * <p>这里再注册一个工厂：{@code languageId} 仍然是 {@code scala}（所以高亮分析器与
 * {@link com.scala.android.project.ScalaLanguageEngine} 补全引擎都是同一套），
 * 只把 {@code getAssociateFileType()} 换成 {@code sbt}。
 * sbt 构建脚本本来就是 Scala（sbt 的 DSL 就是 Scala 的方法调用 + 运算符重载），
 * 复用同一套词法/高亮没有任何问题。
 */
public class SbtLanguageFactory implements LanguageFactory {

    /** sbt 构建脚本的扩展名（{@code build.sbt} / {@code plugins.sbt} …）。 */
    public static final String FILE_EXT = "sbt";

    @Override
    public String getLanguageId() {
        // 与 ScalaLanguageFactory 相同 → 共用同一套语言、引擎与运算符
        return ScalaLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return FILE_EXT;
    }

    @Override
    public Language newLanguage() {
        return new ScalaLanguage();
    }
}
