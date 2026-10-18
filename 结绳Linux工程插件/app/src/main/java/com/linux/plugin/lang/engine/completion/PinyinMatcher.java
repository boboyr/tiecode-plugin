package com.linux.plugin.lang.engine.completion;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.HashMap;
import java.util.Map;

/**
 * 中文补全的拼音匹配工具。
 *
 * <p>采用<b>前缀匹配</b>(而非任意位置包含),避免"输入 t 就命中一堆无关项":
 * <ul>
 *   <li>拼音首字母:如 {@code hzyz} 命中「绘制一帧」;</li>
 *   <li>多音字首字母:如「调」读音有 diao/tiao,输入 {@code t}/{@code ts} 可命中「调试输出」;</li>
 *   <li>完整拼音:如 {@code huizhi} 命中「绘制一帧」。</li>
 * </ul>
 *
 * <p>pinyin4j 单字查询较慢,故按字符缓存其全部读音;缓存超限时整体清空。
 */
public final class PinyinMatcher {

    /** 缓存条目上限,超过则整体清空,防止长会话内存膨胀。 */
    private static final int CACHE_LIMIT = 8192;

    /** 单字 -> 全部拼音(已小写、去声调);非中文映射为 null。 */
    private static final Map<Character, String[]> PINYIN_CACHE = new HashMap<>();

    /** HanyuPinyinOutputFormat 非线程安全,按线程各持一份。 */
    private static final ThreadLocal<HanyuPinyinOutputFormat> FORMAT =
            new ThreadLocal<HanyuPinyinOutputFormat>() {
                @Override
                protected HanyuPinyinOutputFormat initialValue() {
                    return createFormat();
                }
            };

    private PinyinMatcher() {
    }

    private static HanyuPinyinOutputFormat createFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }

    /**
     * 判断 label 是否能以拼音前缀匹配 partial。
     *
     * @param label   补全标签(可含中文)
     * @param partial 已输入的过滤文本
     * @return 首字母前缀或全拼前缀匹配成功时返回 true
     */
    public static boolean matchesPinyin(String label, String partial) {
        if (label == null || label.isEmpty() || partial == null || partial.isEmpty()) {
            return false;
        }
        String query = normalize(partial);
        if (query.isEmpty()) {
            return false;
        }
        if (matchInitials(label, 0, query, 0)) {
            return true;
        }
        return matchFullPinyin(label, 0, query, 0);
    }

    /**
     * 首字母前缀匹配(支持多音字,可回溯)。
     *
     * @param label 标签
     * @param li    label 当前字符下标
     * @param query 已规范化的查询串
     * @param qi    query 已匹配到的下标
     * @return query 全部匹配完成则 true
     */
    private static boolean matchInitials(String label, int li, String query, int qi) {
        if (qi >= query.length()) {
            return true; // 查询串已全部匹配 (前缀匹配成功)
        }
        if (li >= label.length()) {
            return false; // 标签已用完但查询串还有剩余
        }
        char ch = label.charAt(li);
        if (isAsciiAlnum(ch)) {
            if (Character.toLowerCase(ch) == query.charAt(qi)) {
                return matchInitials(label, li + 1, query, qi + 1);
            }
            return false;
        }
        String[] pys = pinyinOf(ch);
        if (pys == null) {
            // 非中文字符(标点等):跳过,不消耗查询串
            return matchInitials(label, li + 1, query, qi);
        }
        for (String py : pys) {
            if (py == null || py.isEmpty()) {
                continue;
            }
            char head = Character.toLowerCase(py.charAt(0));
            if (head == query.charAt(qi) && matchInitials(label, li + 1, query, qi + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 全拼前缀匹配(多点音字,可回溯)。 */
    private static boolean matchFullPinyin(String label, int li, String query, int qi) {
        if (qi >= query.length()) {
            return true;
        }
        if (li >= label.length()) {
            return false;
        }
        char ch = label.charAt(li);
        if (isAsciiAlnum(ch)) {
            if (Character.toLowerCase(ch) == query.charAt(qi)) {
                return matchFullPinyin(label, li + 1, query, qi + 1);
            }
            return false;
        }
        String[] pys = pinyinOf(ch);
        if (pys == null) {
            return matchFullPinyin(label, li + 1, query, qi);
        }
        for (String py : pys) {
            if (py == null || py.isEmpty()) {
                continue;
            }
            String lower = py.toLowerCase();
            if (query.startsWith(lower, qi)
                    && matchFullPinyin(label, li + 1, query, qi + lower.length())) {
                return true;
            }
        }
        return false;
    }

    /** 提取查询串中的有效字符:仅保留 ASCII 字母与数字,并转小写。 */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isAsciiAlnum(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /** 单字转全部拼音(去声调、小写);非中文字符返回 null。 */
    private static String[] pinyinOf(char ch) {
        String[] cached = PINYIN_CACHE.get(ch);
        if (cached != null) {
            return cached;
        }
        String[] result = null;
        try {
            String[] array = PinyinHelper.toHanyuPinyinStringArray(ch, FORMAT.get());
            if (array != null && array.length > 0) {
                // 去重后小写化
                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
                for (String s : array) {
                    if (s != null && !s.isEmpty()) {
                        set.add(s.toLowerCase());
                    }
                }
                if (!set.isEmpty()) {
                    result = set.toArray(new String[0]);
                }
            }
        } catch (BadHanyuPinyinOutputFormatCombination ignored) {
        } catch (Throwable ignored) {
        }
        putCache(ch, result);
        return result;
    }

    private static boolean isAsciiAlnum(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9');
    }

    private static void putCache(char key, String[] value) {
        if (PINYIN_CACHE.size() >= CACHE_LIMIT) {
            PINYIN_CACHE.clear();
        }
        PINYIN_CACHE.put(key, value);
    }
}
