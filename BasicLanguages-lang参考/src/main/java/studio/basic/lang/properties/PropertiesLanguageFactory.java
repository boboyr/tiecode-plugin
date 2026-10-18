package studio.basic.lang.properties;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

public class PropertiesLanguageFactory implements LanguageFactory {
    @Override
    public String getLanguageId() {
        return PropertiesLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "properties";
    }

    @Override
    public Language newLanguage() {
        return new PropertiesLanguage();
    }
}
