package com.goproject.plugin;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.Project;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.Platform;
import com.goproject.plugin.tool.GoCompile;
import com.goproject.plugin.tool.GoCompileApiKeys;
import java.io.IOException;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.goproject.plugin.tool.BaseGoCompiler;
import com.rapid.api.framework.project.ProjectServiceKeys;
import java.io.File;
import com.rapid.util.android.EnvUtils;
import com.goproject.plugin.tool.GoCompileParams;
import android.system.Os;
import android.system.ErrnoException;
import java.util.List;
import java.util.ArrayList;

public class CompilerGoTask extends BaseTask {

	
	@Override
	public void execute() throws TaskInterruptError {
		File output = new File(Platform.getProject().getProjectDir().getAbsolutePath() + "/output");
		if(!output.exists()){
			output.mkdirs();
		}
		GoCompile a = Platform.getService(ProjectServiceKeys.TOOLCHAIN).getTool(GoCompileApiKeys.Gocompile);
		if (!a.isInstalled(AndroidPlugins.getContext(BaseGoCompiler.class))) {
			try {
			    logger.info("安装Go语言编译工具链...");
				a.install(AndroidPlugins.getContext(BaseGoCompiler.class));
			} catch (IOException e) {
			    logger.trace(e);
				throw new RuntimeException("Gxx编译工具链安装失败");
			}
		}
		GoCompileParams goCompileParams = new GoCompileParams();
		goCompileParams.inputFiles = FileUtils.getGofile(Platform.getProject().getChildFile("src"));
		goCompileParams.outputPath = output.getPath();
		goCompileParams.options = new String[]{"build","-o"};
		a.compile(goCompileParams,new CompileOnExecutionCallback());
	}
	
	@Override
	public void prepare(Project project, ProjectVariant variant, Logger mlogger) {
		try {
			logger = mlogger;
            Os.setenv("GOROOT", EnvUtils.getEnvironmentDir(AndroidPlugins.getContext(BaseGoCompiler.class), "linux") + "/go", false);
			Os.setenv("GOCACHE", EnvUtils.getEnvironmentDir(AndroidPlugins.getContext(BaseGoCompiler.class), "linux") + "/go/cache", false);
			//Os.setenv("GOPROXY", "https://goproxy.cn,direct", false);
        } catch (ErrnoException e) {
            e.printStackTrace();
        }
	}
	
	@Override
	public String getId() {
		return "compileWithGo";
	}

	@Override
	public String getName() {
		return "编译到目标平台";
	}
	
	
    
}
