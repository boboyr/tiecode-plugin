package com.scala.android.sbt;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sbt 表达式求值后的值。
 *
 * <p>sbt 的 setting 值是任意 Scala 值；对构建有意义的是下面这几种，
 * 其余一律落成 {@link Kind#UNKNOWN}（保留原文，不阻断构建，只是插件用不到）。
 */
public final class SbtValue {

    public enum Kind {
        STRING,
        NUMBER,
        BOOL,
        /** Seq/List/Vector/Set(...) */
        LIST,
        /** 文件路径（{@code baseDirectory.value / "x"}、{@code file("x")}） */
        PATH,
        /** 依赖坐标（{@code "org" % "name" % "ver"}） */
        MODULE,
        /** Maven 仓库（{@code "name" at "url"}） */
        RESOLVER,
        /** 键引用（{@code baseDirectory.value} 的中间态） */
        KEY,
        MAP,
        NULL,
        UNKNOWN
    }

    public Kind kind = Kind.UNKNOWN;
    public String text;
    public long number;
    public double decimal;
    public boolean decimalValue;
    public boolean bool;
    public File path;
    public List<SbtValue> list;
    public Map<String, SbtValue> map;
    public SbtModuleId module;
    public SbtResolver resolver;

    public static SbtValue of(String value) {
        SbtValue result = new SbtValue();
        result.kind = Kind.STRING;
        result.text = value;
        return result;
    }

    public static SbtValue of(long value) {
        SbtValue result = new SbtValue();
        result.kind = Kind.NUMBER;
        result.number = value;
        return result;
    }

    public static SbtValue of(boolean value) {
        SbtValue result = new SbtValue();
        result.kind = Kind.BOOL;
        result.bool = value;
        return result;
    }

    public static SbtValue of(File value) {
        SbtValue result = new SbtValue();
        result.kind = Kind.PATH;
        result.path = value;
        return result;
    }

    public static SbtValue of(SbtModuleId value) {
        SbtValue result = new SbtValue();
        result.kind = Kind.MODULE;
        result.module = value;
        return result;
    }

    public static SbtValue of(SbtResolver value) {
        SbtValue result = new SbtValue();
        result.kind = Kind.RESOLVER;
        result.resolver = value;
        return result;
    }

    public static SbtValue key(String name) {
        SbtValue result = new SbtValue();
        result.kind = Kind.KEY;
        result.text = name;
        return result;
    }

    public static SbtValue list(List<SbtValue> values) {
        SbtValue result = new SbtValue();
        result.kind = Kind.LIST;
        result.list = values == null ? new ArrayList<>() : values;
        return result;
    }

    public static SbtValue map(Map<String, SbtValue> values) {
        SbtValue result = new SbtValue();
        result.kind = Kind.MAP;
        result.map = values == null ? new LinkedHashMap<>() : values;
        return result;
    }

    public static SbtValue nullValue() {
        SbtValue result = new SbtValue();
        result.kind = Kind.NULL;
        return result;
    }

    public static SbtValue unknown(String text) {
        SbtValue result = new SbtValue();
        result.kind = Kind.UNKNOWN;
        result.text = text;
        return result;
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    /** 尽量取字符串：字符串/数字/布尔/路径都能取到。 */
    public String asString() {
        switch (kind) {
            case STRING:
            case UNKNOWN:
            case KEY:
                return text;
            case NUMBER:
                return decimalValue ? String.valueOf(decimal) : String.valueOf(number);
            case BOOL:
                return String.valueOf(bool);
            case PATH:
                return path == null ? null : path.getAbsolutePath();
            case NULL:
                return null;
            default:
                return null;
        }
    }

    public Integer asInt() {
        switch (kind) {
            case NUMBER:
                return (int) number;
            case STRING:
                try {
                    return Integer.parseInt(text.trim());
                } catch (Exception ignored) {
                    return null;
                }
            default:
                return null;
        }
    }

    public Boolean asBoolean() {
        switch (kind) {
            case BOOL:
                return bool;
            case STRING:
                return Boolean.parseBoolean(text.trim());
            default:
                return null;
        }
    }

    /** 取列表：单个值会被包装成单元素列表。 */
    public List<SbtValue> asList() {
        if (kind == Kind.LIST) {
            return list == null ? new ArrayList<>() : list;
        }
        List<SbtValue> single = new ArrayList<>();
        if (kind != Kind.NULL) {
            single.add(this);
        }
        return single;
    }

    public File asFile() {
        if (kind == Kind.PATH) {
            return path;
        }
        if (kind == Kind.STRING && text != null && !text.isEmpty()) {
            return new File(text);
        }
        return null;
    }

    @Override
    public String toString() {
        switch (kind) {
            case LIST: {
                StringBuilder builder = new StringBuilder("List(");
                for (int i = 0; list != null && i < list.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(list.get(i));
                }
                return builder.append(')').toString();
            }
            case MODULE:
                return String.valueOf(module);
            case RESOLVER:
                return String.valueOf(resolver);
            case MAP:
                return String.valueOf(map);
            default:
                return String.valueOf(asString());
        }
    }
}
