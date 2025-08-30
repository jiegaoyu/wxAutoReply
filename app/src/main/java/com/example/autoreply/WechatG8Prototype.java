package com.example.autoreply;

import android.text.TextUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import de.robv.android.xposed.XposedHelpers;

/** 负责 g8(消息实体) 的字段读取/简要打印/模板保存与构造 */
public class WechatG8Prototype {

    /** 最近一次经过 Hb/tb 的 g8 样本，用于构造发送用对象时参考字段 */
    private static volatile Object sTemplateG8 = null;

    /** 微信自身 userName（如果能学到的话） */
    private static volatile String sSelfUser = null;

    /** g8 类缓存（也会在 MainHook 里缓存）；以 MainHook 为准，必要时兜底 */
    public static Class<?> getG8Class() {
        if (MainHook.sKernelMsgEntityCls != null) return MainHook.sKernelMsgEntityCls;
        try {
            return Class.forName("com.tencent.mm.storage.g8", false, WechatG8Prototype.class.getClassLoader());
        } catch (Throwable ignored) { return null; }
    }

    /* ---------------- 模板&学习 ---------------- */

    public static void saveTemplate(Object g8) {
        if (g8 == null) return;
        sTemplateG8 = g8;
        MainHook.log("[proto] template saved: " + g8);
    }

    public static void learnSelfUser(String self) {
        if (!TextUtils.isEmpty(self)) {
            sSelfUser = self;
            MainHook.log("[learn] self user=" + self);
        }
    }

    /** 从 g8 学习 talkerId 映射 */
    public static void learnTalkerId(Object g8) {
        if (g8 == null) return;
        try {
            String talker = readString(g8, "field_talker");
            Integer tid   = readIntBoxed(g8, "field_talkerId");
            if (!TextUtils.isEmpty(talker) && tid != null && tid > 0) {
                MainHook.putTalkerId(talker, tid);
            }
        } catch (Throwable ignored) {}
    }

    /* ---------------- 关键字段简要输出 ---------------- */

    public static String brief(Object g8) {
        if (g8 == null) return "null";
        try {
            String talker   = readString(g8, "field_talker");
            String from     = readString(g8, "field_fromUsername");
            String to       = readString(g8, "field_toUsername");
            String content  = readString(g8, "field_content");

            Integer isSend  = readIntBoxed(g8, "field_isSend");
            Integer status  = readIntBoxed(g8, "field_status");
            Integer type    = readIntBoxed(g8, "field_type");
            Integer talkerId= readIntBoxed(g8, "field_talkerId");

            Long ct         = readLongBoxed(g8, "field_createTime");
            Long msgId      = readLongBoxed(g8, "field_msgId");
            Long svrId      = readLongBoxed(g8, "field_msgSvrId");
            Long seq        = readLongBoxed(g8, "field_msgSeq");

            StringBuilder sb = new StringBuilder(256);
            sb.append("talker=").append(talker)
              .append(" from=").append(from)
              .append(" to=").append(to)
              .append(" talkerId=").append(talkerId)
              .append(" content=").append(content)
              .append(" isSend=").append(isSend)
              .append(" status=").append(status)
              .append(" type=").append(type)
              .append(" ct=").append(ct)
              .append(" msgId=").append(msgId)
              .append(" svrId=").append(svrId)
              .append(" seq=").append(seq);
            return sb.toString();
        } catch (Throwable t) {
            return "g8=" + g8.getClass().getSimpleName();
        }
    }

    /* ---------------- 读取/写入工具 ---------------- */

    public static String readString(Object obj, String name) {
        if (obj == null) return null;
        try {
            Object v = tryGetField(obj, name);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable ignored) { return null; }
    }

    public static Integer readIntBoxed(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Integer) return (Integer) v;
            if (v == null) return null;
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Throwable ignored) { return null; }
    }

    public static Long readLongBoxed(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Long) return (Long) v;
            if (v == null) return null;
            if (v instanceof Number) return ((Number) v).longValue();
            return Long.parseLong(String.valueOf(v));
        } catch (Throwable ignored) { return null; }
    }

    public static void write(Object obj, String name, Object value) {
        if (obj == null) return;
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) return;
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {}
    }

    private static Object tryGetField(Object obj, String name) throws Throwable {
        try { return XposedHelpers.getObjectField(obj, name); } catch (Throwable ignored) {}
        if (name.startsWith("field_")) {
            String alt = name.substring("field_".length());
            try { return XposedHelpers.getObjectField(obj, alt); } catch (Throwable ignored) {}
        } else {
            String alt = "field_" + name;
            try { return XposedHelpers.getObjectField(obj, alt); } catch (Throwable ignored) {}
        }
        try {
            Field f = findField(obj.getClass(), name);
            if (f != null) {
                f.setAccessible(true);
                return f.get(obj);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        String core = name;
        if (core.startsWith("field_")) core = core.substring("field_".length());
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) {
                String fn = f.getName();
                if (name.equals(fn)) return f;
                if (("field_" + core).equals(fn)) return f;
                if (fn.contains(core)) return f;
            }
        }
        return null;
    }

    /* ---------------- 构造发送用 g8 ---------------- */

    /**
     * 构造一个“待发送”的 g8：
     * - talker = 对方
     * - content = 回复文本
     * - isSend = 1, status = 1, type = 1(文本)
     * - createTime = System.currentTimeMillis()
     */
    public static Object buildOutgoing(String talker, String content) {
        Class<?> g8Cls = getG8Class();
        if (g8Cls == null) {
            MainHook.w("[proto] no g8 class available");
            return null;
        }
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(content)) {
            MainHook.w("[proto] buildOutgoing invalid talker/content");
            return null;
        }
        try {
            Object g8 = null;

            // **优先用模板浅拷贝，避免触发 g8 构造器内部逻辑**
            if (sTemplateG8 != null && g8Cls.isInstance(sTemplateG8)) {
                g8 = shallowClone(sTemplateG8);
                MainHook.log("[proto] buildOutgoing by shallowClone");
            }

            // 兜底再尝试 new
            if (g8 == null) {
                g8 = newInstance(g8Cls);
                MainHook.log("[proto] buildOutgoing by newInstance -> " + g8);
            }
            if (g8 == null) return null;

            // 写关键字段
            write(g8, "field_talker", talker);
            write(g8, "talker", talker);

            write(g8, "field_content", content);
            write(g8, "content", content);

            write(g8, "field_isSend", 1);
            write(g8, "isSend", 1);

            write(g8, "field_status", 1);
            write(g8, "status", 1);

            write(g8, "field_type", 1);
            write(g8, "type", 1);

            long now = System.currentTimeMillis();
            write(g8, "field_createTime", now);
            write(g8, "createTime", now);

            write(g8, "field_msgId", 0L);
            write(g8, "msgId", 0L);

            write(g8, "field_msgSvrId", 0L);
            write(g8, "msgSvrId", 0L);

            write(g8, "field_msgSeq", 0L);
            write(g8, "msgSeq", 0L);

            if (!TextUtils.isEmpty(sSelfUser)) {
                write(g8, "field_fromUsername", sSelfUser);
                write(g8, "fromUsername", sSelfUser);
                write(g8, "field_toUsername", talker);
                write(g8, "toUsername", talker);
            }

            Integer tid = MainHook.getTalkerId(talker);
            if (tid != null && tid > 0) {
                write(g8, "field_talkerId", tid);
                write(g8, "talkerId", tid);
            }

            MainHook.log("[proto] buildOutgoing ok: talker=" + talker + " content=" + content);
            return g8;
        } catch (Throwable t) {
            MainHook.e("[proto] buildOutgoing failed", t);
            return null;
        }
    }

    /* ---------------- 小工具：new/clone ---------------- */

    private static Object newInstance(Class<?> cls) {
        try {
            try {
                Constructor<?> c = cls.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (Throwable ignored) {}

            Constructor<?>[] cs = cls.getDeclaredConstructors();
            Constructor<?> best = null;
            for (Constructor<?> c : cs) {
                if (best == null || c.getParameterTypes().length < best.getParameterTypes().length) {
                    best = c;
                }
            }
            if (best != null) {
                best.setAccessible(true);
                Class<?>[] ps = best.getParameterTypes();
                Object[] args = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) {
                    args[i] = defaultValue(ps[i]);
                }
                return best.newInstance(args);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object shallowClone(Object src) {
        try {
            Class<?> cls = src.getClass();
            Object dst = newInstance(cls);
            if (dst == null) return null;
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                Field[] fs = c.getDeclaredFields();
                for (Field f : fs) {
                    f.setAccessible(true);
                    try { f.set(dst, f.get(src)); } catch (Throwable ignored) {}
                }
            }
            return dst;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == byte.class)    return (byte)0;
        if (t == short.class)   return (short)0;
        if (t == int.class)     return 0;
        if (t == long.class)    return 0L;
        if (t == float.class)   return 0f;
        if (t == double.class)  return 0d;
        if (t == char.class)    return (char)0;
        return null;
    }
}