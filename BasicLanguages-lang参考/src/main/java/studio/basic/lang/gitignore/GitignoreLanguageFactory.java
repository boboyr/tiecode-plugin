package studio.basic.lang.gitignore;

import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.LanguageFactory;

public class GitignoreLanguageFactory implements LanguageFactory {
    @Override
    public String getLanguageId() {
        return GitignoreLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "gitignore";
    }

    @Override
    public Language newLanguage() {
        return new GitignoreLanguage();
    }
}
