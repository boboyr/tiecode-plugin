package com.scala.android.sbt;

import java.util.List;
import java.util.Locale;

/**
 * 「结绳插件工程」识别键。
 *
 * <p>结绳插件工程与普通 Scala 安卓工程的差异只有一个：
 * {@code src/main/resources/META-INF/program.json}（新世代）或
 * {@code AndroidManifest.xml} 里的 {@code <meta-data android:name="tiecode_plugin">}
 * （旧世代 .rmx / .tpk）。
 *
 * <p>build.sbt 侧的识别约定（供 {@link ScalaAndroidProjectManager#isProjectDir} 与
 * {@link SbtBuild} 模型共用）：
 * <ul>
 *   <li>{@code tiecodePlugin := true} —— 显式开关；</li>
 *   <li>或 {@code addSbtPlugin("...tiecode-plugin...")} —— 名字含 {@code tiecode}
 *       / {@code qiplat} / {@code plugin} 的 sbt 插件。</li>
 * </ul>
 */
public final class TiecodePluginKeys {

    /** build.sbt 里的显式开关键。 */
    public static final String KEY_ENABLE = "tiecodePlugin";
    public static final String KEY_ENABLE_PLUGIN = "tiecodePluginEnabled";
    /** AndroidManifest.xml 里旧世代插件开关的 meta-data 名。 */
    public static final String MANIFEST_META_TIECODE_PLUGIN = "tiecode_plugin";
    /** 新世代插件清单（必须打进 rmx 的 META-INF/program.json）。 */
    public static final String PROGRAM_JSON_RELATIVE = "src/main/resources/META-INF/program.json";

    private TiecodePluginKeys() {
    }

    /**
     * 是否声明了"这是结绳插件"。
     *
     * @param build      已解析的 sbt 模型（null 时返回 false）
     * @param manifest   工程的 {@code src/main/AndroidManifest.xml}（可 null）
     */
    public static boolean isTiecodePluginProject(SbtBuild build, String manifestText) {
        if (build == null) {
            return false;
        }
        // 1) 显式键（根作用域或主工程上写了 tiecodePlugin := true 都算）
        if (hasExplicitEnableKey(build)) {
            return true;
        }
        // 2) sbt 插件声明
        if (hasTiecodeSbtPlugin(build)) {
            return true;
        }
        // 3) AndroidManifest.xml 的 meta-data（旧世代 .rmx）
        if (manifestText != null
                && manifestText.contains(MANIFEST_META_TIECODE_PLUGIN)) {
            return true;
        }
        return false;
    }

    /** 根作用域或主工程的 setting 里有没有 tiecodePlugin / tiecodePluginEnabled := true。 */
    private static boolean hasExplicitEnableKey(SbtBuild build) {
        SbtProjectModel main = build.mainProject();
        String enable = build.getString(main, KEY_ENABLE);
        if (enable != null && enable.contains("true")) {
            return true;
        }
        String enablePlugin = build.getString(main, KEY_ENABLE_PLUGIN);
        return enablePlugin != null && enablePlugin.contains("true");
    }

    /** build.sbt 里是否声明了名字带 tiecode/qiplat/plugin 的 sbt 插件。 */
    public static boolean hasTiecodeSbtPlugin(SbtBuild build) {
        if (build == null || build.plugins == null) {
            return false;
        }
        for (SbtPlugin plugin : build.plugins) {
            if (plugin == null || plugin.shortName() == null) {
                continue;
            }
            String name = plugin.shortName().toLowerCase(Locale.ROOT);
            if (name.contains("tiecode") || name.contains("qiplat")) {
                return true;
            }
        }
        return false;
    }

    /** 列出工程里带 tiecode/qiplat/plugin 的 sbt 插件（供日志显示）。 */
    public static List<SbtPlugin> tiecodePlugins(SbtBuild build) {
        java.util.ArrayList<SbtPlugin> result = new java.util.ArrayList<>();
        if (build == null || build.plugins == null) {
            return result;
        }
        for (SbtPlugin plugin : build.plugins) {
            if (plugin == null || plugin.shortName() == null) {
                continue;
            }
            String name = plugin.shortName().toLowerCase(Locale.ROOT);
            if (name.contains("tiecode") || name.contains("qiplat")
                    || name.contains("plugin")) {
                result.add(plugin);
            }
        }
        return result;
    }
}
