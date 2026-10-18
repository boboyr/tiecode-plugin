package com.goproject.plugin.lang;

import android.content.res.AssetManager;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.domain.editor.EditorThemeService;
import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.TextMateLanguageFactory;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.IOException;
import java.io.Reader;

/**
 * Html语言工厂类
 */
public class GoLanguageFactory extends TextMateLanguageFactory {
	/*
    private EditorThemeService editorThemeService;

    public GoLanguageFactory(EditorThemeService editorThemeService) {
        this.editorThemeService = editorThemeService;
		editorThemeService.getColorConfigGroups().
    }
    */
	
    @Override
    public String getLanguageId() {
        return GoLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "go";
    }

    @Override
    public Language newLanguage() {
        try {
            AssetManager assets = AndroidPlugins.getContext(this).getAssets();
            IGrammarSource source = readGrammarSource(
				assets.open("textmate/go.tmLanguage.json"),
				"go.tmLanguage.json"
            );
            Reader reader = readConfiguration(assets.open("textmate/language-configuration.json"));
            IThemeSource themeSource = getThemeSource();

            // 关键：外面必须包一层 GoTexLanguage。
            // 编辑器只认 newLanguage() 的返回值，而真正实现补全分发的是
            // GoTexLanguage.requireAutoComplete（gopls + 内置片段）。
            // 如果这里直接返回裸 GoLanguage，补全就只剩 TextMate 基于文档标识符的
            // "普通补全"，gopls 通道永远不会被调用。
            GoLanguage textMate = new GoLanguage(source, reader, themeSource);
            return new GoTexLanguage(textMate);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
