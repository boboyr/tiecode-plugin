package com.scala.android.androidproject;

import android.graphics.drawable.Drawable;

import com.rapid.api.component.widget.tree.TreeItemViewHolder;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.project.file.ProjectFileUiEvent;
import com.rapid.api.project.DefaultProjectFileManager;
import com.rapid.api.project.structure.PSObject;
import com.rapid.api.project.structure.TreeNode;
import com.rapid.util.android.ContextUtils;
import com.scala.android.R;

/**
 * 工程文件树：给 .scala / .sc / .sbt 文件挂上 Scala 图标。
 *
 * <p>主路径是 {@code ScalaAndroidPlugin#registerFileIcons()} 往宿主 {@code IconService}
 * 注册扩展名图标 —— 文件树的默认绑定就会取到（见宿主 {@code ProjectTreeViewAdapter}）。
 * 这里的 {@link #onBindView} 是同一份图标的二次兜底：宿主在某些树/列表里只走
 * {@code ProjectFileUiEvent}，两边取同一张图，保证不会一处有一处没有。
 */
public class ScalaAndroidFileManager extends DefaultProjectFileManager<ScalaAndroidProject>
        implements ProjectFileUiEvent {

    @Override
    public void onBindView(TreeItemViewHolder holder, TreeNode<PSObject> node) {
        if (holder == null || node == null || node.getSource() == null) {
            return;
        }
        String name = node.getSource().getName();
        if (name == null) {
            return;
        }
        int resId = iconOf(name);
        if (resId == 0) {
            return;
        }
        Drawable drawable = ContextUtils.getDrawable(AndroidPlugins.getContext(this), resId);
        if (drawable != null) {
            holder.loadIcon(drawable);
        }
    }

    /** {@code .scala / .sc} 用 Scala 源码图标，{@code .sbt} 用构建脚本图标，其余返回 0。 */
    static int iconOf(String name) {
        if (name.endsWith(".scala") || name.endsWith(".sc")) {
            return R.drawable.ic_file_scala;
        }
        if (name.endsWith(".sbt")) {
            return R.drawable.ic_file_sbt;
        }
        return 0;
    }
}
