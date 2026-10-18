package com.tiecode.ktcplugin.lang.kt;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.rapid.api.component.widget.tree.TreeItemViewHolder;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.project.file.ProjectFileUiEvent;
import com.rapid.api.project.structure.PSObject;
import com.rapid.api.project.structure.TreeNode;

/**
 * 给文件树里的 {@code .kt} / {@code .kts} 文件挂上 Kotlin 图标。
 *
 * <p>注册方式：{@code program.json} 的 {@code project.event.fileUi} 扩展点
 * （不带 scope 时挂到宿主全局的文件树 UI 事件上），与 scala 插件的
 * {@code ScalaFileManager.onBindView} 做的是同一件事——它是在自定义
 * {@code ProjectFileManager} 里做，本插件没有自己的工程类型，所以走扩展点。
 *
 * <h3>为什么按资源名查找而不是直接写常量</h3>
 * {@code R.drawable.ic_language_kotlin} 的<b>数值</b>由 aapt 分配，改动资源、
 * 重新打包都会变。手工增量打包时 {@code resources.arsc} 可能是上一版的，
 * 写死数值一旦对不上就会画成别的图（甚至取到 launcher 图标）。
 * 所以优先用 {@code getIdentifier} 按名字查，查不到再退回编译期常量。
 */
public class KotlinFileUiEvent implements ProjectFileUiEvent {

    private static final String TAG = "KotlinLang";

    /** 资源名（不含扩展名），对应 res/drawable/ic_language_kotlin.xml。 */
    private static final String ICON_NAME = "ic_language_kotlin";

    /**
     * 候选资源 ID：万一 {@code getIdentifier} 在某个结绳版本上不可用，
     * 还能按数值碰一次——命中前会用 {@code getResourceEntryName} 反查名字确认。
     */
    private static final int[] CANDIDATE_ICON_IDS = {
            0x7f020000,   // 当前 res/drawable 第 0 项（ic_language_kotlin.xml）
            0x7f020001,
    };

    @Override
    public void onBindView(TreeItemViewHolder holder, TreeNode<PSObject> node) {
        if (holder == null || node == null) {
            return;
        }
        PSObject source = node.getSource();
        if (source == null) {
            return;
        }
        if (!isKotlinFile(source.getName())) {
            return;
        }
        Drawable icon = loadIcon();
        if (icon != null) {
            holder.loadIcon(icon);
        }
    }

    private static boolean isKotlinFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".kt") || lower.endsWith(".kts");
    }

    /**
     * 取 Kotlin 图标。
     *
     * <p>顺序：按名字查插件自己的资源 → 用候选数值 ID 并<b>反查名字校验</b> → 放弃。
     *
     * <p>刻意的取舍：这里<b>不</b>引用 {@code R.drawable.ic_language_kotlin}。
     * {@code R} 由 aapt 在构建时生成，本插件的源码里没有生成产物；引用它会让
     * 打包流程依赖「R 必须先于 Java 编译生成」这一顺序。而按名字查找本来就更稳：
     * 数值 ID 会随资源变化漂移，名字不会。
     */
    private static Drawable loadIcon() {
        Context context = context();
        if (context == null) {
            return null;
        }
        Resources resources;
        try {
            resources = context.getResources();
        } catch (Throwable t) {
            return null;
        }
        if (resources == null) {
            return null;
        }
        // 1) 按名字查（最稳）
        try {
            int id = resources.getIdentifier(ICON_NAME, "drawable", context.getPackageName());
            if (id != 0) {
                Drawable drawable = resources.getDrawable(id);
                if (drawable != null) {
                    return drawable;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "按名字取 Kotlin 图标失败，改用候选 ID");
        }
        // 2) 候选数值 ID：必须反查名字确认命中，避免画成路径相同的别的图标
        for (int candidate : CANDIDATE_ICON_IDS) {
            try {
                String name = resources.getResourceEntryName(candidate);
                if (ICON_NAME.equals(name)) {
                    Drawable drawable = resources.getDrawable(candidate);
                    if (drawable != null) {
                        return drawable;
                    }
                }
            } catch (Throwable ignored) {
                // 该 ID 在本包里不存在，继续试下一个。
            }
        }
        return null;
    }

    private static Context context() {
        try {
            return AndroidPlugins.getContext(KotlinFileUiEvent.class);
        } catch (Throwable t) {
            return null;
        }
    }
}
