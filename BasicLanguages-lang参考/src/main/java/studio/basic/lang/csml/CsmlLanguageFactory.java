package studio.basic.lang.csml;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

public class CsmlLanguageFactory implements LanguageFactory {
    @Override
    public String getLanguageId() {
        return CsmlLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "csml";
    }

    @Override
    public Language newLanguage() {
        return new CsmlLanguage();
    }
}
