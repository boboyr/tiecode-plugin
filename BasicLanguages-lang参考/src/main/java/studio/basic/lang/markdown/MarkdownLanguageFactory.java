package studio.basic.lang.markdown;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

public class MarkdownLanguageFactory implements LanguageFactory {
    @Override
    public String getLanguageId() {
        return MarkdownLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "md";
    }

    @Override
    public Language newLanguage() {
        return new MarkdownLanguage();
    }
}
