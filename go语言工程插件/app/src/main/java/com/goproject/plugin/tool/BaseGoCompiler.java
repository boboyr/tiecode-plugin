package com.goproject.plugin.tool;
import com.rapid.api.project.toolchain.LocalBinary;
import android.content.Context;
import com.rapid.api.project.toolchain.Tool.OnExecutionCallback;
import cn.tiecode.api.tool.GxxCompileParams;
import java.io.IOException;
import com.rapid.api.project.toolchain.Tool;
import com.goproject.plugin.lsp.GoplsToolchain;
import com.rapid.util.android.EnvUtils;
import com.rapid.api.Platform;
import java.io.File;
import com.rapid.api.ext.plugin.AndroidPlugins;
import java.util.ArrayList;
import java.util.Collections;

public class BaseGoCompiler extends LocalBinary implements GoCompile {
	
	public BaseGoCompiler(){
		super(a() + "/go/bin/go");
	}
	
	private static File a() {
        return EnvUtils.getEnvironmentDir(AndroidPlugins.getContext(BaseGoCompiler.class), "linux");
    }

	@Override
	public int compile(GoCompileParams goCompileParams, Tool.OnExecutionCallback onExecutionCallback) {
		ArrayList arrayList = new ArrayList();
		String[] strArr = goCompileParams.options;
        if (strArr != null) {
            Collections.addAll(arrayList, strArr);
        }
		arrayList.add(goCompileParams.outputPath);
		arrayList.addAll(goCompileParams.inputFiles);
		//Platform.getLogger().info(Platform.getProject().getChildFile("src").getAbsolutePath());
		//Platform.getLogger().info(String.valueOf(String.join("\n",arrayList)));
        return execute(arrayList, onExecutionCallback);
		//return 0;
	}
	
	@Override
	public void install(Context context) throws IOException {
		// 委托给 GoplsToolchain，保证"编译路径"与"LSP 路径"共用同一份解压逻辑：
		// 版本变化时的清理、gopls 可执行位、缓存目录创建都在那一边。
		if (!GoplsToolchain.install(context)) {
			throw new IOException("Go 工具链安装失败，详见日志");
		}
	}

	@Override
	public boolean isInstalled(Context context) {
		// 以"gopls 可用 + 版本标记匹配"为准，不看 .install.lock：
		// 锁文件在换版本后依然存在，只看锁会把混装的目录误判成已安装。
		return GoplsToolchain.isInstalled(context);
	}

	
	
    
}
