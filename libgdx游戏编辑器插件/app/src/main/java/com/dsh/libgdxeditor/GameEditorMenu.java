package com.dsh.libgdxeditor;

import android.app.Activity;
import android.graphics.drawable.Drawable;

import com.rapid.api.component.widget.editor.Editor;
import com.rapid.api.framework.domain.editor.BaseEditorMenuAction;
import com.rapid.framework.vfs.VirtualFile;

/**
 * 「游戏引擎编辑器」——挂在代码编辑器长按（代码/文本操作）菜单上的入口。
 *
 * 点开后会弹出可视化编辑器：拖组件 -> 生成结绳代码 -> 直接插到光标处。
 */
public class GameEditorMenu extends BaseEditorMenuAction {

    static {
        PluginLog.write("GameEditorMenu 类被宿主加载（静态初始化）");
    }

    public GameEditorMenu() {
        PluginLog.write("GameEditorMenu 已实例化（宿主在注册/构建「代码操作」菜单）");
    }

    @Override
    public String getId() {
        return "ID_LIBGDX_GAME_EDITOR";
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
    public void perform(final Editor editor) {
        PluginLog.write("perform() 被调用：菜单项「游戏引擎编辑器」被点击");
        final Activity activity = hostActivity();
        if (activity == null || editor == null) return;
        String path = null;
        try {
            VirtualFile vf = editor.getVirtualFile();
            if (vf != null) path = vf.getPath();
        } catch (Throwable ignored) {
        }
        String text = null;
        try {
            text = editor.getText() == null ? null : editor.getText().toString();
        } catch (Throwable ignored) {
        }
        try {
            SceneModel model = null;
            String dir = null;
            if (path != null) {
                java.io.File parent = new java.io.File(path).getParentFile();
                if (parent != null) {
                    dir = parent.getParentFile() == null ? parent.getName() : parent.getParentFile().getName();
                }
            }
            // 优先从 .scene.json 读回(含舞台尺寸、类名),元素兜底用代码文本回读
            model = SceneStore.loadModel(path, text);
            SceneModelHolder.setEditor(editor);
            SceneDesignerPage.prepare(model, dir, ProjectAssets.dir(path),
                    ProjectAssets.classNameFor(path), path);
            startPluginPage(SceneDesignerPage.class);
        } catch (Throwable t) {
            PluginLog.write("打开设计布局失败：" + t);
            android.widget.Toast.makeText(activity, "打开设计布局失败：" + t,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private Activity hostActivity() {
        try {
            Activity a = getActivity();
            if (a != null) return a;
        } catch (Throwable ignored) {
        }
        try {
            return getAppCompatActivity();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
