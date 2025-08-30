package com.example.autoreply;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatG8Prototype {

    private static volatile Object   sTemplateG8 = null;   // 保存的一份“内核认可”的 g8
    private static volatile Class<?> sG8Class    = null;   // g8 的类

    public static void saveTemplate(Object g8) {
        if (g8 == null) return;
        sTemplateG8 = g8;
        sG8Class = g8.getClass();
        XposedBridge.log(MainHook.TAG + " [proto] template saved: " + g8 + " g8CL@" + System.identityHashCode(sG8Class));
    }

    /** 从模板克隆出一份，并替换 talker/content/clientMsgId/createTime */
    public static Object cloneAndPatch(String talker, String text) {
        try {
            if (sTemplateG8 == null) {
                XposedBridge.log(MainHook.TAG + " [proto] no template g8 yet");
                return null;
            }
            Object newG8 = null;

            // 1) 先尝试调用 clone（如果 g8 支持）
            try {
                newG8 = XposedHelpers.callMethod(sTemplateG8, "clone");
            } catch (Throwable ignored) {}

            // 2) clone 不可用，则走无参构造 + 复制字段
            if (newG8 == null) {
                try {
                    Class<?> cls = sTemplateG8.getClass();
                    Constructor<?> c = null;
                    try { c = cls.getDeclaredConstructor(); } catch (Throwable ignored) {}
                    if (c != null) {
                        c.setAccessible(true);
                        newG8 = c.newInstance();
                    } else {
                        // 连无参构造都没有，直接 newInstance()（旧式API，某些混淆类仍可用）
                        newG8 = cls.newInstance();
                    }
                    // 复制字段（浅拷贝）到 newG8
                    copyAllFieldsShallow(sTemplateG8, newG8);
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + " [proto] new via default ctor failed: " + t);
                    return null;
                }
            }

            // 3) 修改关键业务字段
            beforeSendPatch(newG8, talker, text);
            return newG8;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [proto] cloneAndPatch error: " + t);
            return null;
        }
    }

    /** 尽量把关键字段都写进去（多种命名/容器兜底） */
    private static void beforeSendPatch(Object g8, String talker, String text) {
        long now = System.currentTimeMillis();
        String cmid = "wxautoreply_" + now + "_" + (int)(Math.random()*100000);

        // 常见直连字段
        trySet(g8, "talker", talker);
        trySet(g8, "content", text);
        trySet(g8, "clientMsgId", cmid);
        trySetLong(g8, "createTime", now);

        // field_* 容器
        trySet(g8, "field_talker", talker);
        trySet(g8, "field_content", text);
        trySet(g8, "field_clientMsgId", cmid);
        trySetLong(g8, "field_createTime", now);

        // 混淆别名（从你的日志看见过 Z1 / a2）
        trySet(g8, "Z1", talker);
        trySet(g8, "a2", text);

        // Holder/AtomicReference 风格
        deepSetIfHolder(g8, "talker", talker);
        deepSetIfHolder(g8, "content", text);
        deepSetIfHolder(g8, "clientMsgId", cmid);
        deepSetLongIfHolder(g8, "createTime", now);

        String t = safeGetStr(g8, "talker", "field_talker", "Z1");
        String c = safeGetStr(g8, "content", "field_content", "a2");
        String id= safeGetStr(g8, "clientMsgId", "field_clientMsgId");
        Long ct  = safeGetLong(g8, "createTime", "field_createTime");
        XposedBridge.log(MainHook.TAG + " [proto][beforeSend] talker="+t+" content="+c+" clientMsgId="+id+" createTime="+ct);
    }

    // ===== 工具：字段访问 =====

    private static void copyAllFieldsShallow(Object src, Object dst) throws IllegalAccessException {
        if (src == null || dst == null) return;
        Class<?> cls = src.getClass();
        while (cls != null && cls != Object.class) {
            Field[] fs = cls.getDeclaredFields();
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue; // 跳过 static
                try {
                    f.setAccessible(true);
                    Object v = f.get(src);

                    // 保守处理：不要把 final 的可变容器原样塞过去（容易共享引用）
                    if (Modifier.isFinal(f.getModifiers())) {
                        // 对不可变类型（String/基本包装/Number等）可以直接赋值
                        if (isSafeImmutable(v)) {
                            f.set(dst, v);
                        } else {
                            // 尝试做一个极浅的替代：数组做 clone，其他容器跳过
                            if (v != null && v.getClass().isArray()) {
                                Object c = cloneArray(v);
                                f.set(dst, c);
                            }
                            // 其他复杂类型就先不复制，避免共享引用导致发送管线状态异常
                        }
                    } else {
                        // 非 final 字段直接赋值（浅拷贝）
                        f.set(dst, v);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private static boolean isSafeImmutable(Object v) {
        return v == null
                || v instanceof String
                || v instanceof Number
                || v instanceof Boolean
                || v instanceof Character;
    }

    private static Object cloneArray(Object arr) {
        try {
            int len = Array.getLength(arr);
            Object copy = Array.newInstance(arr.getClass().getComponentType(), len);
            System.arraycopy(arr, 0, copy, 0, len);
            return copy;
        } catch (Throwable ignored) { return arr; }
    }

    private static void trySet(Object obj, String field, Object val) {
        try { XposedHelpers.setObjectField(obj, field, val); } catch (Throwable ignored) {}
    }
    private static void trySetLong(Object obj, String field, long val) {
        try { XposedHelpers.setLongField(obj, field, val); } catch (Throwable ignored) {}
    }
    private static void deepSetIfHolder(Object obj, String name, Object val) {
        try {
            Object holder = XposedHelpers.getObjectField(obj, name);
            if (holder != null) {
                try { XposedHelpers.callMethod(holder, "set", val); return; } catch (Throwable ignored) {}
                try { XposedHelpers.setObjectField(holder, "value", val); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
    private static void deepSetLongIfHolder(Object obj, String name, long val) {
        try {
            Object holder = XposedHelpers.getObjectField(obj, name);
            if (holder != null) {
                try { XposedHelpers.callMethod(holder, "set", val); return; } catch (Throwable ignored) {}
                try { XposedHelpers.setLongField(holder, "value", val); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
    private static String safeGetStr(Object obj, String... names) {
        for (String n : names) try {
            Object v = XposedHelpers.getObjectField(obj, n);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return null;
    }
    private static Long safeGetLong(Object obj, String... names) {
        for (String n : names) try {
            return XposedHelpers.getLongField(obj, n);
        } catch (Throwable ignored) {}
        return null;
    }
}

