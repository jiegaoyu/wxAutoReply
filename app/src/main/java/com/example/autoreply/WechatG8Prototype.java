package com.example.autoreply;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * “模板克隆法”：clone 一份 g8，并只改最少字段：
 *  talker/content/createTime/clientMsgId/isSend/status
 *  为了确保 clientMsgId 一定写进去，增加了字段+方法的多通道覆盖。
 */
public class WechatG8Prototype {

    private static final String TAG = MainHook.TAG;

    private static volatile Object TEMPLATE_G8 = null;
    private static volatile Class<?> G8_CLASS  = null;

    public static void saveTemplate(Object g8) {
        if (g8 == null) return;
        TEMPLATE_G8 = g8;
        G8_CLASS    = g8.getClass();
        if (MainHook.sKernelMsgEntityCls == null) {
            try { MainHook.sKernelMsgEntityCls = G8_CLASS; } catch (Throwable ignored) {}
        }
        XposedBridge.log(TAG + " [proto] template saved: " + g8 + " g8CL@" + (G8_CLASS != null ? G8_CLASS.hashCode() : 0));
    }

    public static boolean hasTemplate() { return TEMPLATE_G8 != null; }

    public static Object cloneAndPatch(String talker, String text) {
        if (TEMPLATE_G8 == null) {
            XposedBridge.log(TAG + " [proto] no template g8 yet");
            return null;
        }
        final Object proto = TEMPLATE_G8;

        Object g8 = null;
        try {
            g8 = XposedHelpers.callMethod(proto, "clone");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " [proto] clone() fail: " + e);
            try {
                Class<?> clz = proto.getClass();
                g8 = XposedHelpers.newInstance(clz);
                shallowCopyFields(proto, g8);
            } catch (Throwable e2) {
                XposedBridge.log(TAG + " [proto] newInstance fallback fail: " + e2);
                return null;
            }
        }

        // --- 基本字段 ---
        trySetAnyString(g8, talker, "talker", "field_talker", "field_talkerUsername", "username");
        trySetAnyString(g8, text,   "content", "field_content", "msgContent");

        long now = System.currentTimeMillis();
        trySetAnyLong(g8, now, "createTime", "field_createTime", "msgCreateTime", "create_time");

        // 我发出 + 已进入发送流程
        trySetAnyInt(g8, 1, "isSend", "field_isSend");
        trySetAnyInt(g8, 1, "status", "field_status");

        // --- clientMsgId 强制覆盖 ---
        String clientId = "wxautoreply_" + now;
        // 1) 直接字段/AtomicReference/CharSequence
        fillClientMsgIdDeep(g8, clientId);
        // 2) 反射调用一切疑似 setter 的方法
        forceInvokeClientIdSetter(g8, clientId);

        // 打印前置校验
        printVitalFields("beforeSend", g8);
        // 再做一次更严格的 verify 打印（包含 isSend/status）
        printMore("verify",
                "isSend", tryGetInt(g8, "isSend", "field_isSend"),
                "status", tryGetInt(g8, "status", "field_status"));

        return g8;
    }

    // -------------------------------- 工具 --------------------------------

    private static void shallowCopyFields(Object src, Object dst) {
        if (src == null || dst == null) return;
        Class<?> c = src.getClass();
        while (c != null && c != Object.class) {
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    f.set(dst, f.get(src));
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static void trySetAnyString(Object obj, String value, String... names) {
        if (obj == null) return;
        if (names != null) {
            for (String n : names) {
                try { XposedHelpers.setObjectField(obj, n, value); } catch (Throwable ignored) {}
            }
        }
        // 同步写 CharSequence/AtomicReference<CharSequence>
        fuzzySetStringLike(obj, value, names);
    }

    private static void trySetAnyLong(Object obj, long value, String... names) {
        if (obj == null) return;
        if (names != null) {
            for (String n : names) {
                try { XposedHelpers.setLongField(obj, n, value); } catch (Throwable ignored) {}
            }
        }
        fuzzySetNumber(obj, value, true, names);
    }

    private static void trySetAnyInt(Object obj, int value, String... names) {
        if (obj == null) return;
        if (names != null) {
            for (String n : names) {
                try { XposedHelpers.setIntField(obj, n, value); } catch (Throwable ignored) {}
            }
        }
        fuzzySetNumber(obj, value, false, names);
    }

    private static void fuzzySetStringLike(Object obj, String value, String... hints) {
        try {
            Field[] fs = obj.getClass().getDeclaredFields();
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                String name = f.getName().toLowerCase();
                if (!containsAny(name, hints)) continue;

                try {
                    f.setAccessible(true);
                    if (t == String.class || CharSequence.class.isAssignableFrom(t)) {
                        f.set(obj, value);
                    } else if (AtomicReference.class.isAssignableFrom(t)) {
                        Object ar = f.get(obj);
                        if (ar instanceof AtomicReference) {
                            @SuppressWarnings("unchecked")
                            AtomicReference<Object> ref = (AtomicReference<Object>) ar;
                            ref.set(value);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void fuzzySetNumber(Object obj, long v, boolean asLong, String... hints) {
        try {
            Field[] fs = obj.getClass().getDeclaredFields();
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getName().toLowerCase();
                if (!containsAny(name, hints)) continue;

                Class<?> t = f.getType();
                try {
                    f.setAccessible(true);
                    if (asLong) {
                        if (t == long.class) f.setLong(obj, v);
                        else if (t == Long.class) f.set(obj, v);
                    } else {
                        if (t == int.class) f.setInt(obj, (int) v);
                        else if (t == Integer.class) f.set(obj, (int) v);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static boolean containsAny(String name, String... hints) {
        if (name == null || hints == null) return false;
        String s = name.toLowerCase();
        for (String h : hints) {
            if (h == null) continue;
            if (s.contains(h.toLowerCase())) return true;
        }
        return false;
    }

    /** 递归把 clientId 写入所有看起来像 clientMsgId 的位置 */
    private static void fillClientMsgIdDeep(Object root, String clientId) {
        if (root == null) return;
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        dfs(root, clientId, seen, 0, 5);
    }

    @SuppressWarnings("unchecked")
    private static void dfs(Object obj, String clientId, Map<Object, Boolean> seen, int depth, int maxDepth) {
        if (obj == null || seen.containsKey(obj) || depth > maxDepth) return;
        seen.put(obj, Boolean.TRUE);

        Class<?> c = obj.getClass();
        // 定向命名覆盖
        trySetAnyString(obj, clientId,
                "clientMsgId", "field_clientMsgId", "client_msg_id",
                "cliMsgId", "cli_msg_id", "clientid",
                "msgId", "localId", "msgLocalId");

        while (c != null && c != Object.class) {
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Class<?> ft = f.getType();
                    String name = f.getName().toLowerCase();

                    // 字段名判断：包含 client & id，或等于 msgid/localid 等
                    boolean looksLikeClientId =
                            name.contains("client") && name.contains("id")
                                    || name.contains("clientmsgid")
                                    || name.contains("client_msg_id")
                                    || name.equals("msgid")
                                    || name.equals("localid")
                                    || name.equals("msglocalid")
                                    || name.contains("cli_msg_id")
                                    || name.contains("climsgid");

                    Object v = f.get(obj);

                    if (looksLikeClientId) {
                        if (ft == String.class || CharSequence.class.isAssignableFrom(ft)) {
                            try { f.set(obj, clientId); } catch (Throwable ignored) {}
                            continue;
                        }
                        if (AtomicReference.class.isAssignableFrom(ft) && v instanceof AtomicReference) {
                            try { ((AtomicReference) v).set(clientId); } catch (Throwable ignored) {}
                            continue;
                        }
                    }

                    if (v == null) continue;

                    // 递归到子结构
                    if (ft.isArray()) {
                        int len = Array.getLength(v);
                        for (int i = 0; i < len; i++) {
                            dfs(Array.get(v, i), clientId, seen, depth + 1, maxDepth);
                        }
                    } else if (v instanceof Collection) {
                        for (Object e : (Collection<?>) v) {
                            dfs(e, clientId, seen, depth + 1, maxDepth);
                        }
                    } else if (!ft.isPrimitive()
                            && !Number.class.isAssignableFrom(ft)
                            && ft != Boolean.class && ft != Character.class
                            && ft != String.class) {
                        dfs(v, clientId, seen, depth + 1, maxDepth);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    /** 调用一切疑似 clientId 的 setter 方法（单参 String/CharSequence） */
    private static void forceInvokeClientIdSetter(Object obj, String clientId) {
        try {
            Method[] ms = obj.getClass().getMethods(); // public + 继承
            for (Method m : ms) {
                if (m == null) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                String n = m.getName().toLowerCase();
                if (!(n.contains("client") && n.contains("id"))
                        && !n.contains("clientmsgid")
                        && !n.contains("cli_msg_id")
                        && !n.endsWith("msgid")
                        && !n.endsWith("localid")) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps == null || ps.length != 1) continue;

                Class<?> p = ps[0];
                boolean ok = (p == String.class)
                        || CharSequence.class.isAssignableFrom(p)
                        || p == Object.class;
                if (!ok) continue;

                try {
                    m.setAccessible(true);
                    m.invoke(obj, clientId);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void printVitalFields(String stage, Object g8) {
        try {
            String talker  = tryGetString(g8, "talker", "field_talker", "username", "field_talkerUsername");
            String content = tryGetString(g8, "content", "field_content", "msgContent");
            String client  = tryGetString(g8, "clientMsgId", "field_clientMsgId", "client_msg_id",
                    "cliMsgId", "msgId", "localId", "msgLocalId");
            Long   ctime   = tryGetLong  (g8, "createTime", "field_createTime", "msgCreateTime", "create_time");
            XposedBridge.log(TAG + " [proto][beforeSend] talker=" + talker
                    + " content=" + content
                    + " clientMsgId=" + client
                    + " createTime=" + ctime);
        } catch (Throwable ignored) {}
    }

    private static void printMore(String tag, Object... kv) {
        StringBuilder sb = new StringBuilder(TAG).append(" [proto][").append(tag).append("] ");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(kv[i]).append("=").append(kv[i + 1]).append(" ");
        }
        XposedBridge.log(sb.toString());
    }

    private static String tryGetString(Object obj, String... names) {
        for (String n : names) {
            try {
                Object v = XposedHelpers.getObjectField(obj, n);
                if (v instanceof CharSequence) return v.toString();
            } catch (Throwable ignored) {}
        }
        // 再扫 AtomicReference<String/CharSequence>
        try {
            Field[] fs = obj.getClass().getDeclaredFields();
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (AtomicReference.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object ar = f.get(obj);
                    if (ar instanceof AtomicReference) {
                        Object val = ((AtomicReference<?>) ar).get();
                        if (val instanceof CharSequence) return val.toString();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Long tryGetLong(Object obj, String... names) {
        for (String n : names) {
            try { return (Long) XposedHelpers.getObjectField(obj, n); } catch (Throwable ignored) {}
            try { return XposedHelpers.getLongField(obj, n); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Integer tryGetInt(Object obj, String... names) {
        for (String n : names) {
            try { return (Integer) XposedHelpers.getObjectField(obj, n); } catch (Throwable ignored) {}
            try { return XposedHelpers.getIntField(obj, n); } catch (Throwable ignored) {}
        }
        return null;
    }
}

