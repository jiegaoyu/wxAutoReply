package com.example.autoreply;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;

public final class WechatEntityFactory {

    private WechatEntityFactory() {}

    /** 创建 g8 实例：优先无参构造，其次选一个可访问构造并用默认值填充 */
    public static Object newG8Instance(Class<?> g8Cls) {
        if (g8Cls == null) return null;
        try {
            // 1) 无参构造
            try {
                Constructor<?> c0 = g8Cls.getDeclaredConstructor();
                c0.setAccessible(true);
                Object obj = c0.newInstance();
                XposedBridge.log(MainHook.TAG + " [entity] new g8 via no-arg ctor OK");
                return obj;
            } catch (NoSuchMethodException ignore) {
                // 2) 退化：找任意构造并用默认值
                for (Constructor<?> c : g8Cls.getDeclaredConstructors()) {
                    try {
                        c.setAccessible(true);
                        Class<?>[] ps = c.getParameterTypes();
                        Object[] args = new Object[ps.length];
                        for (int i = 0; i < ps.length; i++) {
                            args[i] = defaultValue(ps[i]);
                        }
                        Object obj = c.newInstance(args);
                        XposedBridge.log(MainHook.TAG + " [entity] new g8 via fallback ctor OK");
                        return obj;
                    } catch (Throwable ignore2) { /* try next */ }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [entity] newG8Instance failed: " + t);
        }
        return null;
    }

    /** 尝试把 talker/content 以及常见元数据打到 g8 上；能设几个算几个，返回是否至少设置了 talker 和 content */
    public static boolean populateG8(Object g8, String talker, String text) {
        if (g8 == null) return false;
        int setCnt = 0;

        // 尝试一些常见字段（不强制）
        trySetBySetter(g8, "setType",       int.class,    1);
        trySetBySetter(g8, "setIsSend",     int.class,    1);
        trySetBySetter(g8, "setStatus",     int.class,    1);
        trySetBySetter(g8, "setFlag",       long.class,   0L);
        trySetBySetter(g8, "setCreateTime", long.class,   System.currentTimeMillis());

        // 直接按字段名再试一次（有的类没有 setter）
        trySetByField(g8, "type",       int.class,    1);
        trySetByField(g8, "isSend",     int.class,    1);
        trySetByField(g8, "status",     int.class,    1);
        trySetByField(g8, "flag",       long.class,   0L);
        trySetByField(g8, "createTime", long.class,   System.currentTimeMillis());

        // 重点：talker / content（返回值依赖这两个）
        boolean talkerSet  =
                trySetBySetter(g8, "setTalker",  String.class, talker)
             || trySetByField (g8, "talker",     String.class, talker);
        boolean contentSet =
                trySetBySetter(g8, "setContent", String.class, text)
             || trySetByField (g8, "content",    String.class, text);

        setCnt += talkerSet  ? 1 : 0;
        setCnt += contentSet ? 1 : 0;

        if (!talkerSet || !contentSet) {
            // 最后再“盲写”两个 String 字段，以防字段被混淆成别名
            int blindly = tryWriteTwoStringFields(g8, talker, text);
            setCnt += blindly; // blindly 取 0..2
            XposedBridge.log(MainHook.TAG + " [entity] fallback set two String fields blindly, added=" + blindly);
        }

        boolean ok = setCnt >= 2;
        if (!ok) {
            XposedBridge.log(MainHook.TAG + " [entity] populate g8 failed (talker/content not set)");
        }
        return ok;
    }

    // ---------- helpers ----------

    /** 通过 setter 方法设置（方法名精确匹配） */
    private static boolean trySetBySetter(Object target, String setterName, Class<?> paramType, Object value) {
        if (target == null) return false;
        Class<?> cls = target.getClass();
        try {
            Method m = cls.getDeclaredMethod(setterName, paramType);
            m.setAccessible(true);
            m.invoke(target, value);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 直接通过字段名设置 */
    public static boolean trySetByField(Object target, String fieldName, Class<?> fieldType, Object value) {
        if (target == null) return false;
        Class<?> cls = target.getClass();
        try {
            Field f = findFieldRecursive(cls, fieldName);
            if (f == null) return false;
            if ((f.getModifiers() & Modifier.FINAL) != 0) return false; // 避免 final
            f.setAccessible(true);
            // 简单做一次类型适配
            if (fieldType.isPrimitive()) {
                // 交给反射自动拆装箱
                f.set(target, value);
            } else {
                if (value != null && !fieldType.isAssignableFrom(value.getClass())) {
                    return false;
                }
                f.set(target, value);
            }
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 找到（可能在父类上的）字段 */
    private static Field findFieldRecursive(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 兜底：盲写两个 String 字段（按声明顺序挑前两个 String 非 static/final 的字段） */
    private static int tryWriteTwoStringFields(Object g8, String talker, String text) {
        int wrote = 0;
        try {
            Field[] fs = g8.getClass().getDeclaredFields();
            for (Field f : fs) {
                int mod = f.getModifiers();
                if ((mod & Modifier.STATIC) != 0) continue;
                if ((mod & Modifier.FINAL)  != 0) continue;
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                if (wrote == 0) {
                    f.set(g8, talker);
                    wrote++;
                } else if (wrote == 1) {
                    f.set(g8, text);
                    wrote++;
                    break;
                }
            }
        } catch (Throwable ignore) {}
        return wrote;
    }

    /** 为构造参数提供一个“默认值” */
    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == byte.class)    return (byte) 0;
        if (t == short.class)   return (short) 0;
        if (t == int.class)     return 0;
        if (t == long.class)    return 0L;
        if (t == float.class)   return 0f;
        if (t == double.class)  return 0d;
        if (t == char.class)    return '\0';
        return null;
    }
}

