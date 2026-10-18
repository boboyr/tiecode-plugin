package com.dsh.libgdxeditor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把生成的场景类「合」进当前文件:
 *   文件里已经有同名类 → 整块替换(重复写回不会出现两个同名类)
 *   没有 → 追加到文件末尾(顶层,不会插进别的类里面)
 *
 * <p>注意:这里不再自动往文件头加 {@code @附加资源("../../assets")}——
 * 需要附加资源注解的文件由用户自己维护,插件只负责往同名类里灌生成代码。</p>
 */
public final class CodeMerge {

    private CodeMerge() {
    }

    public static String classNameOf(String code) {
        if (code == null) return null;
        Matcher m = Pattern.compile("^\\s*类\\s+([^\\s::]+)", Pattern.MULTILINE).matcher(code);
        return m.find() ? m.group(1) : null;
    }

    /** 文本里是否已经有名为 name 的顶层类。 */
    public static boolean containsClass(String text, String name) {
        if (text == null || name == null || name.isEmpty()) {
            return false;
        }
        for (String raw : text.split("\n")) {
            String t = raw.trim();
            if (t.equals("类 " + name) || t.startsWith("类 " + name + " ") || t.startsWith("类 " + name + ":")) {
                return true;
            }
        }
        return false;
    }

    /** 文本里 name 这个类是不是「本编辑器生成的场景类」(以「变量 舞台:舞台?」为标志)。 */
    public static boolean isSceneClass(String text, String name) {
        if (text == null || name == null || name.isEmpty()) {
            return false;
        }
        String[] lines = text.split("\n");
        boolean inClass = false;
        for (String raw : lines) {
            String t = raw.trim();
            if (!inClass) {
                if (t.equals("类 " + name) || t.startsWith("类 " + name + " ")
                        || t.startsWith("类 " + name + ":")) {
                    inClass = true;
                }
                continue;
            }
            if (t.equals("结束 类")) {
                return false;
            }
            if (t.startsWith("变量 舞台")) {
                return true;
            }
        }
        return false;
    }

    /** 在文本里挑一个不重名的类名:base、base2、base3... */
    public static String uniqueClassName(String text, String base) {
        String name = base;
        int i = 2;
        while (containsClass(text, name)) {
            name = base + i;
            i++;
        }
        return name;
    }

    /**
     * 决定这次编辑用哪个类名写回:
     * 文件里没有同名类 → 直接用;
     * 同名且它就是本编辑器生成的场景类 → 直接覆盖它;
     * 同名但不是场景类(例如用户的 {@code 类 XXX : 游戏类})→ 换一个不重名的「XXX场景」,
     * 绝不能把用户自己的类整块替换掉。
     */
    public static String resolveSceneClassName(String text, String base) {
        if (base == null || base.isEmpty()) {
            return base;
        }
        if (text == null || text.trim().isEmpty()) {
            return base;
        }
        if (!containsClass(text, base)) {
            return base;
        }
        if (isSceneClass(text, base)) {
            return base;
        }
        // 同名但不是场景类(例如用户的「类 XXX : 游戏类」):
        // 优先复用已经存在的「XXX场景」,避免每次重入都换个新名字
        String alt = base + "场景";
        if (isSceneClass(text, alt)) {
            return alt;
        }
        return uniqueClassName(text, alt);
    }

    /** 把生成代码合进现有文本:同名类整块替换,否则追加到末尾。 */
    public static String merge(String existing, String generated) {
        if (existing == null) existing = "";
        if (generated == null) return existing;
        String name = classNameOf(generated);
        if (name == null || name.isEmpty()) {
            return append(existing, generated);
        }
        String[] lines = existing.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.equals("类 " + name) || t.startsWith("类 " + name + " ") || t.startsWith("类 " + name + ":")) {
                start = i;
                break;
            }
        }
        if (start < 0) return append(existing, generated);
        int end = -1;
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].trim().equals("结束 类")) {
                end = i;
                break;
            }
        }
        if (end < 0) return append(existing, generated);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < start; i++) sb.append(lines[i]).append('\n');
        sb.append(generated);
        if (!generated.endsWith("\n")) sb.append('\n');
        for (int i = end + 1; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i != lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static String append(String existing, String generated) {
        StringBuilder sb = new StringBuilder(existing);
        if (sb.length() > 0 && !existing.endsWith("\n")) sb.append('\n');
        if (sb.length() > 0) sb.append('\n');
        sb.append(generated);
        return sb.toString();
    }
}
