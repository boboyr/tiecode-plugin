package com.goproject.plugin.lang;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.Reader;

import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;

/**
 * Html语言类
 */
public class GoLanguage extends TextMateLanguage {

    public final static String ID = "go";

    public GoLanguage(IGrammarSource grammarSource, Reader languageConfiguration) {
        super(grammarSource, languageConfiguration, null, true);
    }

    public GoLanguage(IGrammarSource grammarSource, Reader languageConfiguration, IThemeSource themeSource) {
        super(grammarSource, languageConfiguration, themeSource, true);
    }

    @Override
    public String getId() {
        return ID;
    }

	@Override
	public String getName() {
		return "go语言";
	}
	
	
}
