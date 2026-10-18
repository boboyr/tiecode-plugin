package com.scala.android;

import android.content.Context;
import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.ServiceKeys;
import com.rapid.api.framework.common.CommonServiceKeys;
import com.rapid.api.framework.common.icon.IconService;
import com.rapid.api.framework.common.setting.SettingsService;
import com.rapid.api.framework.project.toolchain.ToolchainService;
import com.rapid.framework.program.android.DvmPluginDescriptor;
import com.rapid.framework.program.android.app.AndroidPlugin;
import com.rapid.util.android.ContextUtils;
import com.scala.android.setting.ScalaAndroidSettingsBuilder;
import com.scala.android.tool.BaseScalaCompiler;
import com.scala.android.tool.ScalaCompileApiKeys;
import com.scala.android.tool.ScalaToolchain;

/**
 * 插件入口。
 *
 * <p>做四件事：
 * <ol>
 *   <li>把随插件打包的 Scala 3 编译器注册进结绳工具链服务
 *       （{@link ScalaCompileApiKeys#SCALA_COMPILE}），编译任务据此拿到编译器；</li>
 *   <li>清掉上一轮的工程 classpath 钩子（补全用的 classpath 由工程在解析完
 *       build.sbt 后补上，见 {@link ScalaToolchain#setExtraClasspathProvider}）；</li>
 *   <li>注册设置页「Scala安卓工程」；</li>
 *   <li>给 {@code .scala / .sc / .sbt} 注册 Scala 文件图标（见
 *       {@link #registerFileIcons()}）。</li>
 * </ol>
 */
public class ScalaAndroidPlugin extends AndroidPlugin {

    public static final String TAG = "ScalaAndroidPlugin";

    @Override
    public void onInit(DvmPluginDescriptor descriptor) {
        try {
            super.onInit(descriptor);
        } catch (Throwable ignored) {
            // 父类初始化失败也要保证编译器注册继续执行
        }
        try {
            ToolchainService service = Platform.getService(ServiceKeys.TOOLCHAIN);
            if (service == null) {
                service = Platform.getService(com.rapid.api.framework.project.ProjectServiceKeys.TOOLCHAIN);
            }
            service.setTool(ScalaCompileApiKeys.SCALA_COMPILE, new BaseScalaCompiler());
            Log.i(TAG, "已注册 Scala " + ScalaCompileApiKeys.VERSION + " 编译器");
        } catch (Throwable error) {
            Log.e(TAG, "注册 Scala 编译器失败", error);
        }
    }

    @Override
    public void activate() {
        try {
            super.activate();
        } catch (Throwable ignored) {
            // 同上：父类失败不影响本插件的注册
        }
        ScalaToolchain.setExtraClasspathProvider(null);
        registerSettings();
        Log.i(TAG, "scala安卓工程插件 已激活");
    }

    @Override
    public void onUiVisible() {
        try {
            super.onUiVisible();
        } catch (Throwable ignored) {
            // 父类失败不影响图标注册
        }
        registerFileIcons();
    }

    /**
     * 给 {@code .scala / .sc / .sbt} 挂上 Scala 图标。
     *
     * <p>走宿主 {@code IconService.putFileIcon}（与宿主自带的 Java 插件注册
     * {@code .kt / .gradle / .pro} 图标完全同一条路）：文件树的默认绑定本来就是
     * "按扩展名问 IconService 要图标"（见 {@code ProjectTreeViewAdapter}），
     * 所以在这里注册一次，文件树、文件选择器等所有地方都会生效，
     * 不依赖某个 {@code ProjectFileUiEvent} 回调有没有被调。
     *
     * <p>放在 {@code onUiVisible()} 里是因为这时宿主的 Context 与 IconService 都已就绪
     * （宿主自己的插件也是在这个时机注册的）。
     */
    private void registerFileIcons() {
        try {
            Context context = AndroidPlugins.getContext(this);
            if (context == null) {
                Log.w(TAG, "注册 Scala 文件图标：拿不到 Context，跳过");
                return;
            }
            IconService icons = getService(CommonServiceKeys.ICON);
            if (icons == null) {
                Log.w(TAG, "注册 Scala 文件图标：拿不到 IconService，跳过");
                return;
            }
            icons.putFileIcon(ContextUtils.getDrawable(context, R.drawable.ic_file_scala),
                    ".scala", ".sc");
            icons.putFileIcon(ContextUtils.getDrawable(context, R.drawable.ic_file_sbt),
                    ".sbt");
            Log.i(TAG, "已注册 Scala 文件图标：.scala / .sc / .sbt");
        } catch (Throwable error) {
            // 图标注册失败不影响语言/编译功能
            Log.w(TAG, "注册 Scala 文件图标失败", error);
        }
    }

    private void registerSettings() {
        try {
            SettingsService settings = Platform.getService(ServiceKeys.SETTINGS);
            if (settings != null) {
                settings.add(new ScalaAndroidSettingsBuilder());
                Log.i(TAG, "已注册设置页：Scala安卓工程");
            }
        } catch (Throwable error) {
            Log.w(TAG, "注册设置页失败", error);
        }
    }
}
