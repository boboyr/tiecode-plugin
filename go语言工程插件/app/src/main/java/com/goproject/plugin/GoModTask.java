package com.goproject.plugin;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.goproject.plugin.tool.GoCompile;
import com.rapid.api.Platform;
import com.rapid.api.framework.project.ProjectServiceKeys;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.goproject.plugin.tool.GoCompileApiKeys;
import com.goproject.plugin.tool.BaseGoCompiler;
import java.io.IOException;
import com.goproject.plugin.tool.GoCompileParams;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.logging.Logger;

public class GoModTask extends BaseTask {

	@Override
	public void execute() throws TaskInterruptError {
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
	}

	@Override
	public void prepare(Project project, ProjectVariant variant, Logger logger) {
		// 必须覆写：BaseTask.prepare 默认抛 UnsupportedOperationException；
		// 且 logger 字段要在这里赋值，否则 execute() 里 logger.info 会 NPE。
		this.logger = logger;
		this.variant = variant;
	}

	@Override
	public String getId() {
		return "gomodinit";
	}

	@Override
	public String getName() {
		return "模块初始化";
	}
	
    
    
    
}
