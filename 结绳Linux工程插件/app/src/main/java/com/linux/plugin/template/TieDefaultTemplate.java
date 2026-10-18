package com.linux.plugin.template;

import android.content.Context;
import android.graphics.drawable.Drawable;
import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.BaseTemplate;
import com.rapid.api.project.Project;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;
import com.rapid.util.android.AssetUtils;
import java.io.File;
import java.io.IOException;
import com.rapid.util.file.FileUtils;
import com.linux.plugin.project.TieProject;

public class TieDefaultTemplate extends BaseTemplate<TieProject> {
    public static final String DEFAULT_PROJECT_NAME = "我的Linux-SDL3工程";
    public static final String TEMPLATE_NAME = "默认模板";

    public String getName() {
        return TEMPLATE_NAME;
    }

    public Drawable getPicture() {
        return null;
    }

    public String getDefaultProjectName() {
        return DEFAULT_PROJECT_NAME;
    }

    public Form getForm() {
        Form form = new Form();
        return form;
    }

    public void create(TieProject project, Form form, Project.OnProjectCreateListener onProjectCreateListener) {
        Context context = Platform.getApplicationContext();
        try {
            AssetUtils.unZipAssetsFolder(AndroidPlugins.getContext(this), "tieLinux_default.zip", project.getProjectDir().getAbsolutePath());
			AssetUtils.unZipAssetsFolder(AndroidPlugins.getContext(this), "Linux基本库.tlb", project.getChildFile("绳包" + File.separator + "Linux基本库").getPath());
			AssetUtils.unZipAssetsFolder(AndroidPlugins.getContext(this), "SDL3基本库.tlb", project.getChildFile("绳包" + File.separator + "SDL3基本库").getPath());
			//AssetUtils.unZipAssetsFolder(context, "tiecode-toolchain/android/安卓基本库.tlb", project.getChildFile("绳包" + File.separator + "安卓基本库").getPath());
            onProjectCreateListener.onSuccess();
        } catch (IOException e) {
            e.printStackTrace();
            onProjectCreateListener.onFail("工程创建失败");
        }
    }
}
