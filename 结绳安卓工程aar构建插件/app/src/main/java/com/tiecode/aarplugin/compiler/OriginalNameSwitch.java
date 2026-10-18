package com.tiecode.aarplugin.compiler;

/**
 * 「原名输出」开关。
 *
 * <p>只有点「构建aar」时打开：宿主平时的「调试运行 / 构建应用」仍旧用编译器的默认
 * 随机命名（{@code rn_1234}），互不影响。
 */
public final class OriginalNameSwitch {

    private static volatile boolean enabled = false;

    private OriginalNameSwitch() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
