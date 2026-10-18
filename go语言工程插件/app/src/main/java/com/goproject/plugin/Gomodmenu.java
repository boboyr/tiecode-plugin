package com.goproject.plugin;
import com.rapid.api.framework.domain.menu.DomainMenuAction;
import com.rapid.framework.action.menu.MenuAnchor;
import android.graphics.drawable.Drawable;
import com.rapid.framework.data.DataHolder;
import com.rapid.api.framework.domain.menu.BaseDomainMenuAction;
import com.rapid.android.ui.widget.dialog.BottomFormDialog;
import com.qiplat.open.ui.widget.dialog.QiBottomTextInputDialog;

public class Gomodmenu extends BaseDomainMenuAction {

	@Override
	public void perform(DataHolder arg) {
	}

	@Override
	public MenuAnchor getAnchor() {
		return null;
	}

	@Override
	public Drawable getIcon() {
		return null;
	}

	@Override
	public String getId() {
		return "gomod";
	}

	@Override
	public String getTitle() {
		return "模块管理";
	}
    
    
}
