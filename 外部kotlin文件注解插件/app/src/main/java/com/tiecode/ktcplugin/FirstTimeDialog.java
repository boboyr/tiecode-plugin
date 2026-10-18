package com.tiecode.ktcplugin;

import com.qiplat.open.ui.widget.dialog.QiBottomDialog;
import com.rapid.api.Platform;
import com.rapid.api.project.Project;
import com.rapid.api.project.component.BaseComponent;
import com.rapid.api.project.component.Component;
import com.rapid.api.project.component.PersistenceConfig;
import com.rapid.api.project.component.StatePersistent;
import com.rapid.api.framework.ServiceKeys;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.CodeProject;
import java.io.File;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.util.android.AssetUtils;
import java.io.IOException;

public class FirstTimeDialog extends BaseComponent implements StatePersistent<FirstTimeDialog> {
    boolean isFirstTime = true;

    @Override
    public void didOpenProject(Project project) {
	if(Platform.getProjectFramework().getManager(project).getIdentifier()=="cn.tiecode.android"){
		File classpath = new File(AndroidPlugins.getPluginDir(this),"classpath");
		if(!classpath.exists()){
			try {
				AssetUtils.unZipAssetsFolder(AndroidPlugins.getContext(this), "kotlin-stdlib.zip", classpath.getPath());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		Task compileWithTiecodec = ((CodeProject)project).getTasks().getTask("compileWithTiecodec");
		Task compileWithJavac = ((CodeProject)project).getTasks().getTask("compileWithJavac");
		Task compileWithKotlinc = new KotlincompileTask();
		project.getTasks().addTask(compileWithKotlinc);
		compileWithJavac.dependsOn(new Task[]{compileWithTiecodec,compileWithKotlinc});
	   }
    }

    @Override
    public FirstTimeDialog getState() {
        return this;
    }

    @Override
    public void loadState(FirstTimeDialog firstTimeDialog) {
        this.isFirstTime = firstTimeDialog.isFirstTime;
    }

    @Override
    public PersistenceConfig getPersistenceConfig() {
        return new PersistenceConfig(Project.FILE_PERSISTENCE_STORAGE);
    }
}
