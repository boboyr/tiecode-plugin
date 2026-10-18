package com.goproject.plugin.project;

import android.graphics.drawable.Drawable;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.project.file.DefaultOpeningOption;
import com.rapid.api.project.CodeProjectManager;
import com.rapid.api.project.TypeDescription;
import com.rapid.util.android.ContextUtils;
import java.io.File;
import com.goproject.plugin.lang.GoLanguage;
import com.goproject.plugin.lang.operator.GoOperatorProvider;
import com.goproject.plugin.project.template.GoConsoleTemplate;
import com.goproject.plugin.project.template.GoSdl3Template;
import com.goproject.plugin.R;
import com.goproject.plugin.lang.GoLanguageFactory;

/**
 * 「go语言工程」项目管理器（go语言控制台插件 + go语言SDL3工程插件 的合并版）。
 *
 * <p>一个工程类型，两个创建模板：
 * <ul>
 *   <li>{@link GoConsoleTemplate}「控制台工程」；</li>
 *   <li>{@link GoSdl3Template}「SDL3工程」。</li>
 * </ul>
 * 工程对象 {@link GoProject} 在打开时按目录内容自动装配控制台/SDL3 两套构建流水线。
 */
public class GoProjectManager extends CodeProjectManager<GoProject> {
    public final static String TYPE = "go语言工程";
    public final static String IDENTIFIER = "sample.go";

    public GoProjectManager() {
        super();
        this.fileManager = new GoFileManager();
        //fileManager.enableProjectJSONFilter();
        fileManager.addPSObjectOpeningOption(DefaultOpeningOption.INSTANCE, ".go",".mod",".sum");
        // External Libraries 标记文件（无扩展名）：单击不弹编辑器，展开进外部库。
        fileManager.addPSObjectOpeningOption(
                new com.goproject.plugin.structure.NoopOpeningOption(),
                com.goproject.plugin.project.GoProject.EXTERNAL_LIBRARIES_MARKER);
        // 文件树「External Libraries」节点：GO SDK 源码 + 模块缓存里的依赖。
        fileManager.addPSObjectExpansionCase(
                new com.goproject.plugin.structure.ExternalLibrariesExpansionCase(),
                com.goproject.plugin.project.GoProject.EXTERNAL_LIBRARIES_MARKER);
        // 长按文件夹 → 新建文件 → 新建go文件（输入 main 确认即新建 main.go）。
        fileManager.addPSObjectCreationOption(
                new com.goproject.plugin.structure.GoFileCreationOption());
		languageManager.registerEngine("go",GoLanguageEngine.getInstance());
		languageManager.registerLanguageFactory(new GoLanguageFactory());
        languageManager.registerOperatorProvider(GoLanguage.ID, new GoOperatorProvider());
        // 两个创建模板：控制台工程 / SDL3工程
        addTemplate(new GoConsoleTemplate());
        addTemplate(new GoSdl3Template());
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public TypeDescription getTypeDescription() {
        Drawable drawable = ContextUtils.getDrawable(
                AndroidPlugins.getContext(this),
                R.drawable.golang
        );
        return new TypeDescription(TYPE, TypeDescription.APPLICATION, drawable);
    }

    @Override
    public GoProject newProject() {
        return new GoProject();
    }

    @Override
    public boolean isProjectDir(File file) {
        return false;
    }
}
