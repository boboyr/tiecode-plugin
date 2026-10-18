package studio.basic.lang.json;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

public class JSONLanguageFactory implements LanguageFactory {
    @Override
    public String getLanguageId() {
        return JSONLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "json";
    }

    @Override
    public Language newLanguage() {
        return new JSONLanguage();
    }
}
