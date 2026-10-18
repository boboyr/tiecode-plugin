package com.scala.android.util;

/**
 * 插件清单（{@code META-INF/program.json}）的小工具。
 *
 * <p>为什么需要它：宿主装载插件时读的是
 * {@code <Programs>/<插件id>/program.json} 这份<b>目录级副本</b>
 * （{@code ProgramManagerImpl} 枚举目录下的 {@code program.json}，
 * {@code AndroidPluginWrapper} 再用其中的 {@code "file"} 找插件包），
 * 而这份副本是宿主首次安装时写下的快照。覆盖安装只替换 {@code .rmx} 时，
 * 结绳重启后仍按旧快照加载 —— 改了扩展点/入口类/版本号都不生效。
 * 所以覆盖安装后要用 rmx 里的清单重新生成这份副本。
 *
 * <p>刻意不引 JSON 库（插件只依赖宿主 API）：清单结构固定，做定点替换足够，
 * 而且这里是纯字符串处理，可以脱离 Android 单独测试。
 */
public final class PluginManifest {

    private PluginManifest() {
    }

    /**
     * 把清单里的 {@code "file"} 指向真正的插件包文件名（宿主用它在插件目录里找包）。
     *
     * <p>已经有 {@code "file"} 就替换值，没有就补在最后一个 {@code }} 之前。
     *
     * @param manifest  rmx 里 {@code META-INF/program.json} 的原文
     * @param fileName  插件包在插件目录里的文件名（如 {@code 我的插件.rmx}）
     * @return 新的清单文本；{@code manifest} 为 null 时返回 null
     */
    public static String withFileName(String manifest, String fileName) {
        if (manifest == null) {
            return null;
        }
        String quoted = quote(fileName);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"file\"\\s*:\\s*\"[^\"]*\"")
                .matcher(manifest);
        if (matcher.find()) {
            return matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement("\"file\":" + quoted));
        }
        int close = manifest.lastIndexOf('}');
        if (close < 0) {
            return manifest;
        }
        String head = manifest.substring(0, close);
        int end = head.length();
        while (end > 0 && Character.isWhitespace(head.charAt(end - 1))) {
            end--;
        }
        boolean hasComma = end > 0 && head.charAt(end - 1) == ',';
        String separator = hasComma ? "\n  " : ",\n  ";
        return head.substring(0, end) + separator + "\"file\":" + quoted + "\n"
                + manifest.substring(close);
    }

    /** JSON 字符串转义。 */
    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        return builder.append('"').toString();
    }
}
