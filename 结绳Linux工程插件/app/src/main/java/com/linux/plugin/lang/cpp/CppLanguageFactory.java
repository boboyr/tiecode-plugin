package com.linux.plugin.lang.cpp;

import android.content.res.AssetManager;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.domain.editor.EditorThemeService;
import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.TextMateLanguageFactory;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.IOException;
import java.io.Reader;
import com.rapid.api.Platform;

/**
 * Html语言工厂类
 */
public class CppLanguageFactory extends TextMateLanguageFactory {
	/*
    private EditorThemeService editorThemeService;

    public CppLanguageFactory(EditorThemeService editorThemeService) {
        this.editorThemeService = editorThemeService;
    }
*/
    @Override
    public String getLanguageId() {
        return CppLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "cpp";
    }

    @Override
    public Language newLanguage() {
        try {
            AssetManager assets = Platform.getActivity().getAssets();
            IGrammarSource source = readGrammarSource(
				assets.open("textmate/cpp/cpp.tmLanguage.json"),
				"cpp.tmLanguage.json"
            );
            Reader reader = readConfiguration(assets.open("textmate/cpp/language-configuration.json"));
            IThemeSource themeSource = getThemeSource();
            CppLanguage language = new CppLanguage(source, reader, themeSource);
            return language;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
