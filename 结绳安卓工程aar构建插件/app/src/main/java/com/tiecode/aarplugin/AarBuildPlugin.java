package com.tiecode.aarplugin;

import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.api.framework.common.CommonServiceKeys;
import com.rapid.api.framework.common.setting.SettingsService;
import com.rapid.framework.program.android.app.AndroidPlugin;
import com.tiecode.aarplugin.compiler.OriginalNameCompilerPlugin;
import com.tiecode.aarplugin.setting.AarSettingsBuilder;

/**
 * 插件入口。
 *
 * <p>激活时往结绳编译器里装配「原名输出」插件（真正生效与否由运行配置「构建aar」控制），
 * 任务链与运行配置由 {@link AarProjectComponent} 在工程打开时挂上。
 */
public class AarBuildPlugin extends AndroidPlugin {

    public static final String TAG = "AarBuildPlugin";

    @Override
    public void activate() {
        OriginalNameCompilerPlugin.install();
        registerSettings();
        Log.i(TAG, "结绳安卓工程aar构建插件 已激活");
    }

    /** 设置 → 项目管理器 → 「AAR/JAR 打包」。 */
    private void registerSettings() {
        try {
            SettingsService settings = Platform.getService(CommonServiceKeys.SETTINGS);
            if (settings != null) {
                settings.add(new AarSettingsBuilder());
                Log.i(TAG, "已注册设置页：AAR/JAR 打包");
            }
        } catch (Throwable t) {
            Log.w(TAG, "注册设置页失败", t);
        }
    }
}
