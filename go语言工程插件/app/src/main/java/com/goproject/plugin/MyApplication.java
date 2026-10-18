package com.goproject.plugin;

import com.rapid.framework.program.android.app.AndroidPlugin;
import com.rapid.framework.program.android.DvmPluginDescriptor;
import com.rapid.api.framework.project.toolchain.ToolchainService;
import com.rapid.api.framework.project.ProjectServiceKeys;
import com.goproject.plugin.tool.GoCompileApiKeys;
import com.goproject.plugin.tool.BaseGoCompiler;
import com.rapid.api.Platform;
import com.goproject.plugin.lsp.bridge.GoplsLogger;
import com.goproject.plugin.lsp.GoplsSession;

/**
 * 合并插件入口（go语言控制台插件 + go语言SDL3工程插件）。
 *
 * <p>继承 {@link AndroidPlugin}：
 * <ul>
 *   <li>onInit：注册控制台工程用的 go 编译工具链（{@code Gocompile}）；</li>
 *   <li>SDL3 工程的「Go 工具链 + Android GCC」由构建任务
 *       {@code BuildLibmainTask} 自己按需安装和调用（用 ProcessBuilder 直接起进程，
 *       因为 go 命令行必须控制工作目录和环境变量，这比走 ToolchainService 更直接）。</li>
 * </ul>
 */
public class MyApplication extends AndroidPlugin {

	@Override
	public void onInit(DvmPluginDescriptor descriptor) {
		ToolchainService service = Platform.getService(ProjectServiceKeys.TOOLCHAIN);
		service.setTool(GoCompileApiKeys.Gocompile, new BaseGoCompiler());
	}

	@Override
	public void activate() {

	}

	/**
	 * 插件卸载时断开 LSP 会话<b>并结束 gopls 进程</b>。
	 *
	 * <p>gopls 由插件自己拉起，不显式结束就会常驻后台；
	 * 重新加载插件时又会拉起一个，久了堆出多个 40MB 级进程。
	 */
	@Override
	public void onUninstall() {
		try {
			// 用 shutdownProcess() 而不是 shutdown()：后者只断开 LSP 连接，
			// 而 gopls 是插件自己拉起的子进程，不结束的话会一直常驻后台占内存。
			GoplsSession.get().shutdownProcess();
			GoplsLogger.get().info("插件卸载，已断开 gopls 并结束其进程");
		} catch (Throwable ignore) {
			// 卸载路径上不能抛异常。
		}
	}
}
