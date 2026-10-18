package com.tiecode.aarplugin.setting;

import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.api.framework.project.ProjectServiceKeys;
import com.rapid.api.framework.project.toolchain.ToolchainService;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.java.JavaCompiler;
import com.tiecode.aarplugin.AarBuildPlugin;

/**
 * 「打包用 Java 版本」设置项。
 *
 * <p>存 {@code Platform.getSharedPreferences()}。构建开始时把宿主工具链里的
 * {@link Tool#JAVA_COMPILER} 临时换成 {@link VersionedJavaCompiler}（只改
 * {@code -source/-target}），构建结束后原样换回来 —— 所以这个选择只影响
 * 「构建aar / 构建jar」，平时调试/构建应用还是宿主自己的编译器版本。
 */
public final class JavaVersionSetting {

    public static final String KEY = "AARPLUGIN_JAVA_VERSION";

    /** 跟随宿主（默认） */
    public static final String DEFAULT = "默认";
    /** 1.8：Android 兼容性最好，javac 的旧式版本号写法 */
    public static final String LEGACY = "1.8";
    /** 9 ~ 25（javac 从 JDK 9 起接受不带 "1." 的版本号） */
    public static final int MIN_VERSION = 9;
    public static final int MAX_VERSION = 25;
    /** 可选项：默认, 1.8, 9, 10, ... 25 */
    public static final String[] OPTIONS = buildOptions();

    /** 构建期间保存宿主原来的编译器，用于还原 */
    private static JavaCompiler previousCompiler;

    private JavaVersionSetting() {
    }

    private static String[] buildOptions() {
        String[] options = new String[MAX_VERSION - MIN_VERSION + 3];
        options[0] = DEFAULT;
        options[1] = LEGACY;
        for (int i = MIN_VERSION; i <= MAX_VERSION; i++) {
            options[i - MIN_VERSION + 2] = String.valueOf(i);
        }
        return options;
    }

    public static String get() {
        try {
            String value = Platform.getSharedPreferences().getString(KEY);
            if (value == null || value.isEmpty()) {
                return DEFAULT;
            }
            return value;
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    public static void set(String value) {
        try {
            Platform.getSharedPreferences().putString(KEY, value == null ? DEFAULT : value);
            Platform.getSharedPreferences().commit();
        } catch (Throwable t) {
            Log.w(AarBuildPlugin.TAG, "保存 Java 版本设置失败", t);
        }
    }

    public static boolean isEnabled() {
        return !DEFAULT.equals(get());
    }

    /** 设置页上显示的文字。 */
    public static String describe() {
        String value = get();
        if (DEFAULT.equals(value)) {
            return "默认（跟随宿主编译器）";
        }
        return value + "（编译出 Java " + value + " 字节码）";
    }

    /** 构建开始：按设置临时替换 Java 编译器。 */
    public static synchronized void apply() {
        if (!isEnabled() || previousCompiler != null) {
            return;
        }
        try {
            ToolchainService tools = Platform.getService(ProjectServiceKeys.TOOLCHAIN);
            if (tools == null) {
                return;
            }
            JavaCompiler current = tools.getTool(Tool.JAVA_COMPILER);
            if (current == null) {
                return;
            }
            previousCompiler = current;
            tools.setTool(Tool.JAVA_COMPILER, new VersionedJavaCompiler(current, get()));
            Log.i(AarBuildPlugin.TAG, "本次构建使用 Java 版本：" + get());
        } catch (Throwable t) {
            Log.w(AarBuildPlugin.TAG, "切换 Java 版本失败，将使用宿主默认编译器", t);
        }
    }

    /** 构建结束：还原宿主的 Java 编译器。 */
    public static synchronized void restore() {
        if (previousCompiler == null) {
            return;
        }
        try {
            ToolchainService tools = Platform.getService(ProjectServiceKeys.TOOLCHAIN);
            if (tools != null) {
                tools.setTool(Tool.JAVA_COMPILER, previousCompiler);
            }
        } catch (Throwable t) {
            Log.w(AarBuildPlugin.TAG, "还原 Java 编译器失败", t);
        } finally {
            previousCompiler = null;
        }
    }
}
