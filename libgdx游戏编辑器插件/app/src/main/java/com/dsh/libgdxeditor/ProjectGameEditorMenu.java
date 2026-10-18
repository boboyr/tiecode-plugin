package com.dsh.libgdxeditor;

import android.app.Activity;
import android.graphics.drawable.Drawable;

import com.rapid.api.framework.domain.menu.BaseDomainMenuAction;
import com.rapid.api.framework.domain.menu.DomainMenuAction;
import com.rapid.framework.data.DataHolder;

/** 工程界面右上角菜单里的同一个入口（没有当前文件时，代码会复制到剪贴板）。 */
public class ProjectGameEditorMenu extends BaseDomainMenuAction {

    static {
        PluginLog.write("ProjectGameEditorMenu 类被宿主加载（静态初始化）");
    }

    public ProjectGameEditorMenu() {
        PluginLog.write("ProjectGameEditorMenu 已实例化（工程右上角菜单）");
    }

    @Override
    public String getId() {
        return "ID_LIBGDX_GAME_EDITOR_DOMAIN";
    }

    @Override
    public String getTitle() {
        return "游戏引擎编辑器";
    }

    @Override
    public Drawable getIcon() {
        return null;
    }

    @Override
    public boolean isEnabled(DataHolder dataHolder) {
        return true;
    }

    @Override
    public void perform(DataHolder dataHolder) {
        Activity a = null;
        try {
            a = getActivity();
        } catch (Throwable ignored) {
        }
        if (a == null) {
            try {
                a = getAppCompatActivity();
            } catch (Throwable ignored) {
            }
        }
        if (a == null) return;
        SceneModelHolder.setEditor(null);
        SceneDesignerPage.prepare(new SceneModel(), null, ProjectAssets.dir(null), null, null);
        startPluginPage(SceneDesignerPage.class);
    }
}
