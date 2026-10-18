package com.linux.plugin.project;

import android.graphics.drawable.Drawable;

import com.rapid.api.Platform;
import com.rapid.api.project.CodeProjectManager;
import com.rapid.api.project.TypeDescription;
import com.rapid.util.android.ContextUtils;
import java.io.File;
import com.rapid.api.project.ProjectManager;
import com.linux.plugin.template.TieDefaultTemplate;
import com.rapid.api.framework.project.file.DefaultOpeningOption;
import com.rapid.util.file.FileUtils;
import com.rapid.api.project.service.LanguageManager;
import com.rapid.framework.extension.annotation.Import;
import com.rapid.api.Unit;
import com.linux.plugin.lang.engine.TieLanguage;
import com.linux.plugin.lang.engine.TieLanguageFactory;
import com.linux.plugin.lang.engine.TieLanguageEngine;
import com.linux.plugin.lang.cpp.CppLanguageFactory;
import com.linux.plugin.lang.go.GoLanguageFactory;
import com.linux.plugin.lang.go.LspGoIntegration;


public class TieProjectManager extends CodeProjectManager<TieProject> {
	
    public static final String IDENTIFIER = "tie";
    public static final String TYPE = "结绳Linux-SDL3工程";
	
    public TieProjectManager() {
        addTemplate(new TieDefaultTemplate());
		fileManager = new TieFileManager();
        fileManager.enableProjectJSONFilter();
        // 可打开的源码文件类型:结绳 .t 与 C/C++ 源文件。
        fileManager.addPSObjectOpeningOption(DefaultOpeningOption.INSTANCE,
                "t", "c", "cpp", "cc", "cxx", "c++", "h", "hpp", "hh", "hxx", "h++", "inl", "ipp","go");
		languageManager.registerEngine("tie",TieLanguageEngine.getInstance());
		
		languageManager.registerLanguageFactory(new TieLanguageFactory());
        languageManager.registerLanguageFactory(new CppLanguageFactory());
		languageManager.registerLanguageFactory(new GoLanguageFactory());

        // 装配 gopls 通道（Go 的补全/跳转/诊断都靠它）。
        // 这里只装配、不连接——工程目录要到 load() 才知道。
        LspGoIntegration.install(languageManager);
    }

    public TypeDescription getTypeDescription() {
        Drawable drawable = ContextUtils.getDrawable(Platform.getApplicationContext(), 0x7f080186);
        return new TypeDescription(TYPE, 1, drawable);
    }

    public boolean isProjectDir(File file) {
        return true;
    }

    public TieProject newProject() {
        return new TieProject();
    }

    public String getIdentifier() {
        return IDENTIFIER;
    }

	@Override
	public void load(TieProject project, ProjectManager.LoadingPresentation presentation) {
		//FileUtils.delete(project.getChildFile("绳包" + File.separator + "Linux基本库"));
		super.load(project, presentation);
		// 打开工程时连上 Termux 里的 gopls；workspace 会自动定位到含 go.mod 的目录。
		LspGoIntegration.openProject(project.getProjectDir());
	}

	@Override
	public void delete(TieProject project) {
		// 关工程时断开 gopls，并把诊断缓存清掉——
		// 否则下次打开新工程会看到上一个工程残留的错误提示。
		LspGoIntegration.closeProject();
		super.delete(project);
	}
	
}

