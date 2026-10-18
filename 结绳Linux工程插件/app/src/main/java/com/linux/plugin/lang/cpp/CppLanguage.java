package com.linux.plugin.lang.cpp;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.Reader;

import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;

public class CppLanguage extends TextMateLanguage {

    public final static String ID = "cpp";

    public CppLanguage(IGrammarSource grammarSource, Reader languageConfiguration) {
        super(grammarSource, languageConfiguration, null, true);
    }

    public CppLanguage(IGrammarSource grammarSource, Reader languageConfiguration, IThemeSource themeSource) {
        super(grammarSource, languageConfiguration, themeSource, true);
    }

    @Override
    public String getId() {
        return ID;
    }

	@Override
	public String getName() {
		return "c++";
	}
	
	
}
