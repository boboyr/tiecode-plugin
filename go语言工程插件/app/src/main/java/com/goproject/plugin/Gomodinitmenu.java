package com.goproject.plugin;

import com.rapid.api.framework.domain.menu.BaseDomainMenuAction;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.qiplat.open.ui.widget.dialog.QiBottomTextInputDialog;
import com.rapid.api.Platform;
import com.rapid.framework.data.DataHolder;
import com.goproject.plugin.lsp.GoplsToolchain;

/**
 * 「模块管理 → 初始化」菜单。
 *
 * <p>弹出输入对话框：输入模块名称，<b>输入框默认填有 main</b>；
 * 确认后相当于在工程 src 目录执行 {@code go mod init <模块名>}（默认 main）。
 */
public class Gomodinitmenu extends BaseDomainMenuAction {

	/** 输入框默认的模块名。 */
	private static final String DEFAULT_MODULE = "main";

	@Override
	public String getParentId() {
		return "gomod";
	}

	@Override
	public void perform(DataHolder arg) {
		final QiBottomTextInputDialog qi = QiBottomTextInputDialog.make(getActivity());
		qi.setTitle("初始化模块");
		qi.setInputTitle("模块名称");
		qi.setInputHint("请输入模块名");
		// 输入框默认填 main，确认后即 go mod init main
		qi.setText(DEFAULT_MODULE);
		qi.setPositiveButton("确定", new View.OnClickListener() {

				@Override
				public void onClick(View v) {
					String name = qi.getText();
					if (name == null || name.trim().isEmpty()) {
						name = DEFAULT_MODULE;
					}
					Platform.getLogger().info("go mod init " + name.trim());
					openConsole("dalvikvm;clear;"
							+ GoEnv.consoleEnvPrefix()
							+ "cd " + Platform.getProject().getChildFile("src") + ";"
							+ GoplsToolchain.goBinary(GoplsToolchain.pluginContext()).getAbsolutePath()
							+ " mod init " + name.trim());
				}

			});

		qi.getDialog().show();
	}

	@Override
	public Drawable getIcon() {
		return null;
	}

	@Override
	public String getId() {
		return "gomodinit";
	}

	@Override
	public String getTitle() {
		return "初始化";
	}
}
