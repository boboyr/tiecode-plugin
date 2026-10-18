package com.goproject.plugin;
import com.rapid.api.project.toolchain.Tool.LogLevel;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.Platform;

public class CompileOnExecutionCallback implements com.rapid.api.project.toolchain.Tool.OnExecutionCallback {

	@Override
	public void onPrintLine(Tool.LogLevel logLevel, String string) {
		Platform.getLogger().info(string);
	}

    
    
    
}
