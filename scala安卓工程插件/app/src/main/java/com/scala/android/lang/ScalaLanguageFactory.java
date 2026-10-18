package com.scala.android.lang;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

/**
 * Scala 语言工厂：把 {@code .scala} 文件关联到 {@link ScalaLanguage}。
 *
 * <p>不使用 TextMate：插件侧的 TextMate 语法依赖宿主的
 * {@code TextMateLanguage}/tm4e 与主题联动，容易出现"语言对象创建失败 →
 * 既没高亮也没补全"的连锁问题。结绳语言（结绳Linux工程插件）用的正是这里的
 * {@link LanguageFactory} + {@code DecisionBaseAnalyzer} 通道。
 */
public class ScalaLanguageFactory implements LanguageFactory {

    public static final String FILE_EXT = "scala";

    @Override
    public String getLanguageId() {
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
