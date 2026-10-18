package com.linux.plugin.lang.engine;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

/** 结绳语言工厂:把 .t 文件关联到 {@link TieLanguage}。 */
public class TieLanguageFactory implements LanguageFactory {

    public static final String FILE_EXT = "t";

    @Override
    public String getLanguageId() {
        return TieLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return FILE_EXT;
    }

    @Override
    public Language newLanguage() {
        return new TieLanguage();
    }
}
