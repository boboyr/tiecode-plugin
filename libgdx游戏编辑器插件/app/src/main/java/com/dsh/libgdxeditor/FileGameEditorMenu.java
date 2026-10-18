package com.dsh.libgdxeditor;

import android.app.Activity;
import android.graphics.drawable.Drawable;

import com.rapid.api.framework.project.file.BaseFileMenuAction;
import com.rapid.api.project.structure.PSObject;
import com.rapid.api.project.structure.TreeNode;
import com.rapid.framework.vfs.VirtualFile;

/**
 * 工程文件树里的同一个入口（长按/右键文件 → 游戏引擎编辑器）。
 * 拿得到文件路径，所以生成的代码能直接插到那个文件里，场景也会存在它旁边。
 */
public class FileGameEditorMenu extends BaseFileMenuAction {

    static {
        PluginLog.write("FileGameEditorMenu 类被宿主加载（静态初始化）");
    }

    public FileGameEditorMenu() {
        PluginLog.write("FileGameEditorMenu 已实例化（工程文件菜单）");
    }

    @Override
    public String getId() {
        return "ID_LIBGDX_GAME_EDITOR_FILE";
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
    public void perform(TreeNode<PSObject> node) {
        PluginLog.write("文件菜单 perform() 被调用");
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
        String path = null;
        try {
            PSObject obj = node == null ? null : node.getSource();
            VirtualFile vf = obj == null ? null : obj.getVirtualFile();
            if (vf != null) path = vf.getPath();
        } catch (Throwable ignored) {
        }
        SceneModel model;
        String dir = null;
        String text = null;
        if (path != null) {
            dir = new java.io.File(path).getParentFile() == null
                    ? null : new java.io.File(path).getParentFile().getName();
            text = SceneStore.readFile(path);
        }
        // 优先从 .scene.json 读回(含舞台尺寸、类名),元素兜底用代码文本回读
        model = SceneStore.loadModel(path, text);
        SceneModelHolder.setEditor(null);
        SceneDesignerPage.prepare(model, dir, ProjectAssets.dir(path),
                ProjectAssets.classNameFor(path), path);
        startPluginPage(SceneDesignerPage.class);
    }
}
