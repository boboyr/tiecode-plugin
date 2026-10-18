package com.goproject.plugin.project.running;

import com.rapid.api.Platform;
import com.rapid.api.component.widget.window.Window;
import com.rapid.api.ext.framework.BaseObject;
import com.rapid.api.framework.DataKeys;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.ExecutorResult;
import com.goproject.plugin.GoEnv;
import com.goproject.plugin.project.GoProject;
import com.rapid.api.project.component.running.RunConfiguration.ResultPresentation;
import com.rapid.api.project.component.running.RunConfiguration.ReadyPresentation;
import com.rapid.api.project.component.running.RunConfiguration;
import com.rapid.util.android.EnvUtils;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.goproject.plugin.tool.BaseGoCompiler;
import com.goproject.plugin.FileUtils;
import java.util.List;
import com.goproject.plugin.CompilerGoTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.goproject.plugin.project.b;
import io.github.rosemoe.sora.widget.CodeEditor;

public class GoStartRunConfig extends TaskRunConfiguration {
	
	public void i(){
		List<String> goFileList = FileUtils.getGofile(Platform.getProject().getChildFile("src")); // 传入你的go文件所在文件夹路径（别留空！）
	    String goFiles = String.join(" ", goFileList);
	    // 带 Go 环境（GOROOT/GOCACHE/GOPROXY=https://goproxy.cn,direct 等）再运行：
	    // 控制台里 HOME 常不可写，不带 GOCACHE 时 go run 会报 "GOCACHE is not defined"。
	    // cd 到 src（go.mod 所在目录），第三方 import 才能按模块解析。
	    // 工具链由本配置等待的 RunGoTask（编译并运行go文件）先安装，走到这里说明 go 已就绪。
	    openConsole("dalvikvm;clear;"
				+ GoEnv.consoleEnvPrefix()
				+ "cd " + Platform.getProject().getChildFile("src") + ";"
				+ com.goproject.plugin.lsp.GoplsToolchain.goBinary(
						com.goproject.plugin.lsp.GoplsToolchain.pluginContext()).getAbsolutePath()
				+ " run " + goFiles);
	}
	
	public Project getProject() {
        return Platform.getProject();
    }

    public String getTaskId() {
        return "RunWithGo";
    }

    public ProjectVariant getVariant() {
        return ProjectVariant.DEBUG;
    }

    public void onResult(ExecutorResult executorResult) {
		if (executorResult == ExecutorResult.SUCCESS) {
            runUI(new b(this));
        }
    }

    public String getId() {
        return "ts-run";
    }

    public String getName() {
        return "调试运行";
    }

}
