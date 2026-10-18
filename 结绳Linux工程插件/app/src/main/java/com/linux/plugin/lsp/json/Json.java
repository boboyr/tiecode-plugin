package com.linux.plugin.lsp.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：解析为 {@code Map/List/String/Integer/Long/Double/Boolean/null}，
 * 与 Android 自带 {@code org.json} 的对象模型保持一致（这样上层两种实现可互换）。
 *
 * <p>不依赖任何第三方库，也不依赖 Android API，便于单元测试与独立编译。
 */
public final class Json {

    private Json() {
    }

    /** 空对象。 */
    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    /**
     * 创建对象并按键值对顺序填充。
     *
     * <p>用法：{@code Json.obj("jsonrpc", "2.0", "id", 1)}。奇数个参数会抛异常。
     */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对出现，当前参数个数: " + keyValues.length);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /** 空数组。 */
    public static List<Object> arr() {
        return new ArrayList<>();
    }

    /** 创建数组并填充元素。 */
    public static List<Object> arr(Object... items) {
        List<Object> list = new ArrayList<>(items.length);
        for (Object item : items) {
            list.add(item);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 安全读取：全部容忍 null 与类型不符，返回默认值而不是抛异常
    // ------------------------------------------------------------------

    /** 读取子对象；不存在或类型不符时返回 null。 */
    public static Map<String, Object> object(Object value) {
        return value instanceof Map ? castMap(value) : null;
    }

    /** 读取指定键的子对象；不存在或类型不符时返回 null。 */
    public static Map<String, Object> object(Map<String, Object> parent, String key) {
        return parent == null ? null : object(parent.get(key));
    }

    /** 读取数组；不存在或类型不符时返回空列表（永不返回 null）。 */
    public static List<Object> array(Object value) {
        if (value instanceof List) {
            return castList(value);
        }
        return new ArrayList<>();
    }

    /** 读取指定键的数组；不存在或类型不符时返回空列表（永不返回 null）。 */
    public static List<Object> array(Map<String, Object> parent, String key) {
        return parent == null ? null : array(parent.get(key));
    }

    /** 读取字符串；Number/Boolean 会被转换，其余情况返回 fallback。 */
    public static String string(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return fallback;
    }

    /** 读取指定键的字符串。 */
    public static String string(Map<String, Object> parent, String key, String fallback) {
        return parent == null ? fallback : string(parent.get(key), fallback);
    }

    /** 读取 int；支持 Number 与数字字符串。 */
    public static int integer(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 读取指定键的 int。 */
    public static int integer(Map<String, Object> parent, String key, int fallback) {
        return parent == null ? fallback : integer(parent.get(key), fallback);
    }

    /** 读取 boolean；兼容 "true"/"1" 这类字符串。 */
    public static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
                return false;
            }
        }
        return fallback;
    }

    /** 读取指定键的 boolean。 */
    public static boolean bool(Map<String, Object> parent, String key, boolean fallback) {
        return parent == null ? fallback : bool(parent.get(key), fallback);
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /** 序列化为紧凑 JSON 文本。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                // JSON 不允许 NaN/Infinity，按 LSP 惯例降级为 null。
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, entry.getKey());
                sb.append(':');
                writeTo(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Object[]) {
            sb.append('[');
            Object[] items = (Object[]) value;
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeTo(sb, items[i]);
            }
            sb.append(']');
        } else if (value instanceof CharSequence) {
            writeString(sb, value.toString());
        } else {
            // 兜底：按字符串写入，避免产出非法 JSON。
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析 JSON 文本。
     *
     * @throws JsonException 文本非法时抛出
     */
    public static Object read(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("待解析的 JSON 文本为 null");
        }
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("JSON 末尾存在多余内容，位置: " + parser.position());
        }
        return value;
    }

    /** 解析并断言根节点为对象。 */
    public static Map<String, Object> readObject(String text) throws JsonException {
        Object value = read(text);
        Map<String, Object> map = object(value);
        if (map == null) {
            throw new JsonException("JSON 根节点不是对象: " + text);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    /** 递归下降解析器。 */
    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        int position() {
            return index;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() throws JsonException {
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("JSON 意外结束");
            }
            char c = text.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        Map<String, Object> parseObject() throws JsonException {
            Map<String, Object> map = new LinkedHashMap<>();
            index++; // '{'
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(index) != '"') {
                    throw new JsonException("对象键必须是字符串，位置: " + index);
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || text.charAt(index) != ':') {
                    throw new JsonException("对象键后缺少 ':'，位置: " + index);
                }
                index++;
                map.put(key, parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("对象未闭合");
                }
                char c = text.charAt(index);
                if (c == ',') {
                    index++;
                    continue;
                }
                if (c == '}') {
                    index++;
                    return map;
                }
                throw new JsonException("对象中出现意外字符 '" + c + "'，位置: " + index);
            }
        }

        List<Object> parseArray() throws JsonException {
            List<Object> list = new ArrayList<>();
            index++; // '['
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("数组未闭合");
                }
                char c = text.charAt(index);
                if (c == ',') {
                    index++;
                    continue;
                }
                if (c == ']') {
                    index++;
                    return list;
                }
                throw new JsonException("数组中出现意外字符 '" + c + "'，位置: " + index);
            }
        }

        String parseString() throws JsonException {
            index++; // '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("字符串转义未完成");
                }
                char esc = text.charAt(index++);
                switch (esc) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            throw new JsonException("\\u 转义长度不足");
                        }
                        String hex = text.substring(index, index + 4);
                        index += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("非法的 \\u 转义: " + hex);
                        }
                        break;
                    default:
                        throw new JsonException("无法识别的转义字符 '\\" + esc + "'");
                }
            }
        }

        Object parseNumber() throws JsonException {
            int start = index;
            if (!atEnd() && (text.charAt(index) == '-' || text.charAt(index) == '+')) {
                index++;
            }
            boolean floating = false;
            while (!atEnd()) {
                char c = text.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    index++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, index);
            if (token.isEmpty()) {
                throw new JsonException("无法解析的记号，位置: " + start);
            }
            try {
                if (floating) {
                    return Double.valueOf(token);
                }
                long value = Long.parseLong(token);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
                return value;
            } catch (NumberFormatException e) {
                throw new JsonException("非法的数字: " + token);
            }
        }

        void expect(String literal) throws JsonException {
            if (!text.startsWith(literal, index)) {
                throw new JsonException("期望 \"" + literal + "\"，位置: " + index);
            }
            index += literal.length();
        }
    }
}
