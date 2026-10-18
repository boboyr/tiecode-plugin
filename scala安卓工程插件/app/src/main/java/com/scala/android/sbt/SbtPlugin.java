package com.scala.android.sbt;

/**
 * {@code project/plugins.sbt} 里 {@code addSbtPlugin(...)} 声明的一个 sbt 插件。
 */
public final class SbtPlugin {

    public final SbtModuleId module;
    /** 声明所在文件。 */
    public String file;
    public int line;

    public SbtPlugin(SbtModuleId module, String file, int line) {
        this.module = module;
        this.file = file;
        this.line = line;
    }

    /** 坐标里的插件名，例如 {@code sbt-modern-android}。 */
    public String shortName() {
        return module == null ? "" : module.name;
    }

    /**
     * 是否是「Android 工程」插件（sbt-modern-android 系列）。
     * 命中时才会启用 {@code platformTarget/androidApplicationId/...} 这些键。
     */
    public boolean isAndroidPlugin() {
        String name = shortName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("sbt-android")
                || lower.contains("sbt-modern-android")
                || lower.contains("android-sdk-plugin")
                || lower.contains("sbt-android-sdk");
    }

    @Override
    public String toString() {
        return shortName() + " " + (module == null ? "" : module.revision);
    }
}
