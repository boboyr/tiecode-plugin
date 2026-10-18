package com.scala.android.lang;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

/**
 * 让 {@code *.sc}（Scala 脚本）也走 Scala 语言。
 *
 * <p>与 {@link SbtLanguageFactory} 同理：宿主按<b>扩展名</b>找语言工厂，
 * 只注册 {@code scala} 的话，{@code .sc} 文件同样没有高亮与补全。
 * 这里共用同一个 {@code languageId = scala}，所以语言、引擎、运算符都是同一套。
 */
public class ScalaScriptLanguageFactory implements LanguageFactory {

    /** Scala 脚本扩展名。 */
    public static final String FILE_EXT = "sc";

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
