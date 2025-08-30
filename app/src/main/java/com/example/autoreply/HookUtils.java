package com.example.autoreply;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class HookUtils {
    private HookUtils() {}

    /** 兼容查找：优先精确匹配，找不到就遍历宽松匹配 */
    public static Method findMethodIfExists(Class<?> cls, String name, Class<?>... params) {
        try {
            // 消除 varargs 警告：显式转型为 Object[]
            Method m = XposedHelpers.findMethodExactIfExists(cls, name, (Object[]) params);
            if (m != null) return m;
        } catch (Throwable ignored) {}
        // 兜底：宽松匹配（名称一致 & 形参可赋值）
        outer:
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != params.length) continue;
            for (int i = 0; i < ps.length; i++) {
                if (!(params[i] == ps[i] || ps[i].isAssignableFrom(params[i]))) {
                    continue outer;
                }
            }
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** 发送路径调试日志（primitive 版本） */
    public static void logHbBeforePrimitive(String talker, String text, boolean a, boolean b) {
        XposedBridge.log(MainHook.TAG + " [Hb.before(primitive)] talker=" + talker + " text=" + text + " a=" + a + " b=" + b);
    }
    public static void logHbAfterPrimitive(Object result) {
        XposedBridge.log(MainHook.TAG + " [Hb.after(primitive)] result=" + String.valueOf(result));
    }

    /** 反射调用的默认值 */
    public static Object defaultValue(Class<?> c) {
        if (!c.isPrimitive()) return null;
        if (c == boolean.class) return false;
        if (c == byte.class)    return (byte) 0;
        if (c == short.class)   return (short) 0;
        if (c == char.class)    return (char) 0;
        if (c == int.class)     return 0;
        if (c == long.class)    return 0L;
        if (c == float.class)   return 0f;
        if (c == double.class)  return 0d;
        return null;
    }

    /** 日志用的内容预览：去换行/制表，截断到 80 字符 */
    public static String preview(String s) {
        if (s == null) return "null";
        String one = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        final int MAX = 80;
        if (one.length() <= MAX) return one;
        return one.substring(0, MAX) + "…(" + one.length() + ")";
    }
}

