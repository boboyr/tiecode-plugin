package studio.basic.lang.xml;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

public class XmlLanguageFactory implements LanguageFactory {
    @Override
    public String getLanguageId() {
        return XmlLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "xml";
    }

    @Override
    public Language newLanguage() {
        return new XmlLanguage();
    }
}
