package com.goproject.plugin.project.running;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.component.running.RunConfiguration;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.ExecutorResult;
import com.rapid.api.Platform;

public class ElfRunConfig extends TaskRunConfiguration {
/*
	@Override
	public void run(RunConfiguration.ResultPresentation presentation) {
		//openConsole("dalvikvm;clear;" + str);
		//openConsole( "dalvikvm;clear;" + "/data/user/0/com.tiecode.develop/files/env/linux/go/bin/go run /storage/emulated/0/Tiecode/Projects/我的go语言工程/main.go");
		openConsole( "dalvikvm;clear;cd /data/user/0/com.tiecode.develop/files/env/linux/hello;export GODEBUG=netdns=go+netgo;" + "/data/user/0/com.tiecode.develop/files/env/linux/go/bin/go env -w GOPROXY=https://goproxy.cn,direct;/data/user/0/com.tiecode.develop/files/env/linux/go/bin/go get github.com/ulikunitz/xz@latest");
	}
*/
	public Project getProject() {
        return Platform.getProject();
    }

    public String getTaskId() {
        return "compileWithGo";
    }

    public ProjectVariant getVariant() {
        return ProjectVariant.DEBUG;
    }

    public void onResult(ExecutorResult executorResult) {
    }

    public String getId() {
        return "default-elf-compie";
    }

    public String getName() {
        return "构建二进制文件";
    }
	
    
    
    
}
