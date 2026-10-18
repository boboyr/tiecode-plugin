package com.goproject.plugin;

import com.rapid.api.framework.domain.menu.BaseDomainMenuAction;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.qiplat.open.ui.widget.dialog.QiBottomTextInputDialog;
import com.rapid.api.Platform;
import com.rapid.framework.data.DataHolder;
import com.goproject.plugin.lsp.GoplsToolchain;

public class Gomodlydmenu extends BaseDomainMenuAction {

	@Override
	public String getParentId() {
		return "gomod";
	}


	@Override
	public void perform(DataHolder arg) {
		final QiBottomTextInputDialog qi = QiBottomTextInputDialog.make(getActivity());
		qi.setTitle("添加依赖");
		qi.setInputTitle("依赖链接");
		qi.setInputHint("请输入链接");
		qi.setPositiveButton("确定", new View.OnClickListener(){

				@Override
				public void onClick(View v) {
					Platform.getLogger().info(qi.getText());
					// 带 Go 环境（GOPROXY=https://goproxy.cn,direct 等），
					// 否则默认走 proxy.golang.org，部分网络下 dial tcp ... i/o timeout。
					openConsole("dalvikvm;clear;"
							+ GoEnv.consoleEnvPrefix()
							+ "cd " + Platform.getProject().getChildFile("src") + ";"
							+ GoplsToolchain.goBinary(GoplsToolchain.pluginContext()).getAbsolutePath()
							+ " get " + qi.getText());
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
		return "gomodlyd";
	}

	@Override
	public String getTitle() {
		return "下载依赖";
	}




}
