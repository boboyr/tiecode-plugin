package com.dsh.libgdxeditor;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.ContextThemeWrapper;

/**
 * 宿主那套底部弹窗({@code QiBottomTextInputDialog} / {@code dialog_bottom_input})里有
 * {@code QiButton}(MaterialButton),inflate 时会校验主题必须是 {@code Theme.MaterialComponents} 的后代。
 *
 * <p>插件页跑在 {@code SimplePluginActivity} 上,它的 manifest 主题是 {@code Theme.AppCompat.Light}
 * (不是 Material),直接把自己的 Context 传进去会在第 99 行 inflate 崩:
 * {@code The style on this component requires your app theme to be Theme.MaterialComponents}。
 * 宿主应用(application)的主题是 {@code AppTheme -> QiUI -> Theme.MaterialComponents.*},
 * 所以这里用应用主题 id 包一层 {@link ContextThemeWrapper} 再交给弹窗。</p>
 *
 * <p>注意 {@link ContextWrapper#getApplicationContext()} 返回的是 Application(不是 Activity),
 * 拿不到 Activity 时再退回 {@code Platform.getActivity()}。</p>
 */
public final class UiContext {

    private UiContext() {
    }

    /** 返回一个带宿主应用主题(MaterialComponents 后代)的 Context;拿不到时退回原 Context。 */
    public static Context material(Context base) {
        if (base == null) {
            return null;
        }
        try {
            Activity host = activityOf(base);
            if (host == null) {
                // 插件页本身是 ContextThemeWrapper,getApplicationContext() 是 Application;
                // 这里再问一次宿主当前的 Activity 兜底
                host = com.rapid.api.Platform.getActivity();
            }
            if (host != null) {
                int theme = host.getApplicationInfo().theme;
                if (theme != 0) {
                    return new ContextThemeWrapper(base, theme);
                }
            }
        } catch (Throwable t) {
            PluginLog.write("material context 失败:" + t);
        }
        return base;
    }

    /** 沿 ContextWrapper 链找 Activity(页面自己不是 Activity,但包着宿主 Activity)。 */
    private static Activity activityOf(Context context) {
        Context current = context;
        for (int i = 0; current != null && i < 8; i++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (current instanceof ContextWrapper) {
                current = ((ContextWrapper) current).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }
}
