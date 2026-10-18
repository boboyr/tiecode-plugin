package com.dsh.nomount;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 极简反射工具：只为把宿主里那几个 hidden API 用起来。 */
final class Reflect {

    private Reflect() {
    }

    static Object getStatic(Class<?> type, String method, Object... args) throws Exception {
        return call(type, null, method, args);
    }

    static Object call(Class<?> type, Object target, String method, Object... args) throws Exception {
        Method m = findMethod(type, method, args);
        if (m == null) {
            throw new NoSuchMethodException(type.getName() + "#" + method);
        }
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, Object[] args) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (args[i] == null) {
                        if (p[i].isPrimitive()) {
                            ok = false;
                            break;
                        }
                    } else if (!wrap(p[i]).isInstance(args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        return c;
    }

    private static Class<?>[] types(Object[] args) {
        Class<?>[] out = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        return out;
    }

    static Object field(Object target, String name) {
        try {
            Field f = findField(target.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            if (f == null) return false;
            f.setAccessible(true);
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
