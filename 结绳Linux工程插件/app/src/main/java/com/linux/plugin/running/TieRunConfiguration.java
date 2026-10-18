package com.linux.plugin.running;

import com.rapid.api.Platform;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import java.io.File;
import com.rapid.util.android.EnvUtils;
import com.linux.plugin.page.SDLPluginPage;

public class TieRunConfiguration extends TaskRunConfiguration {

    public Project getProject() {
        return Platform.getProject();
    }

    public String getTaskId() {
        return "copySoFile";
    }

    public ProjectVariant getVariant() {
        return ProjectVariant.DEBUG;
    }

    public void onResult(ExecutorResult executorResult) {
		
		if (executorResult == ExecutorResult.SUCCESS) {
            runUI(new b(this));
        }
    }
	
	public void i() {
		File file = new File(EnvUtils.getEnvironmentDir(Platform.getApplicationContext(), "linux"), "tmp");
		if (!file.exists()) {
			file.mkdir();
		}
		String str = file + File.separator + "libmain.so";
        //openConsole("dalvikvm;clear;" + str);
		startPluginPage(SDLPluginPage.class);
    }

    public String getId() {
        return "ts-run";
    }

    public String getName() {
        return "调试构建运行";
    }
}

