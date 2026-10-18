package com.goproject.plugin;

import com.rapid.api.framework.domain.menu.BaseDomainMenuAction;
import android.graphics.drawable.Drawable;
import com.rapid.framework.data.DataHolder;
import com.rapid.api.Platform;
import com.goproject.plugin.lsp.GoplsToolchain;

/**
 * 「模块管理 → 更新依赖」菜单：整理并同步工程依赖。
 *
 * <p>相当于在工程 src 目录执行 {@code go mod tidy}。
 * 带 {@link GoEnv#consoleEnvPrefix()} 的 Go 环境
 * （GOPROXY=https://goproxy.cn,direct 等），避免官方代理不可达时超时。
 */
public class Gomodupdatemenu extends BaseDomainMenuAction {

	@Override
	public String getParentId() {
		return "gomod";
	}

	@Override
	public void perform(DataHolder arg) {
		Platform.getLogger().info("go mod tidy");
		openConsole("dalvikvm;clear;"
				+ GoEnv.consoleEnvPrefix()
				+ "cd " + Platform.getProject().getChildFile("src") + ";"
				+ GoplsToolchain.goBinary(GoplsToolchain.pluginContext()).getAbsolutePath()
				+ " mod tidy");
	}

	@Override
	public Drawable getIcon() {
		return null;
	}

	@Override
	public String getId() {
		return "gomodupdate";
	}

	@Override
	public String getTitle() {
		return "更新依赖";
	}
}
