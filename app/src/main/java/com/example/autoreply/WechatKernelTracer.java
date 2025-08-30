package com.example.autoreply;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class WechatKernelTracer {

    private static volatile boolean sInstalled = false;

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        try {
            final Class<?> i8Cls = XposedHelpers.findClass("com.tencent.mm.storage.i8", cl);
            final Class<?> g8Cls = findG8Class(cl);

            // Hb(g8, boolean, boolean)
            hookHb(i8Cls, g8Cls);
            hookHb(i8Cls, Object.class); // 兜底

            // tb(g8)
            hookTb(i8Cls, g8Cls);
            hookTb(i8Cls, Object.class); // 兜底

            // Ic(long, g8, boolean)
            hookIc(i8Cls, g8Cls);
            hookIc(i8Cls, Object.class); // 兜底

            XposedBridge.log(MainHook.TAG + " WechatKernelTracer installed.");
            sInstalled = true;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " WechatKernelTracer install fail: " + t);
        }
    }

    private static Class<?> findG8Class(ClassLoader cl) {
        try {
            return XposedHelpers.findClass("com.tencent.mm.storage.g8", cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // region hook Hb
    private static void hookHb(Class<?> i8Cls, Class<?> g8Param) {
        try {
            XposedHelpers.findAndHookMethod(i8Cls, "Hb", g8Param, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    logBefore(param);
                    try {
                        final Object g8 = param.args[0];
                        final boolean a = toBool(param.args[1]);
                        final boolean b = toBool(param.args[2]);

                        // 入向消息：a=false, b=true
                        if (!a && b && g8 != null) {
                            // 先存模板，保证 AutoResponder 能拿到
                            WechatG8Prototype.saveTemplate(g8);

                            // 取关键信息并触发自动回复
                            String talker  = getStr(g8, "field_talker", "talker");
                            String content = getStr(g8, "field_content", "content");

                            // 可选：提前修正时间与 cmid（不会写回，这里只打印）
                            Long   ct      = getLong(g8, "field_createTime", "createTime");
                            String cmid    = getStr(g8, "field_clientMsgId", "clientMsgId");

                            XposedBridge.log(MainHook.TAG + " [MSG:IN] talker=" + talker
                                    + " len=" + (content == null ? 0 : content.length()));
                            if (ct != null || cmid != null) {
                                XposedBridge.log(MainHook.TAG + " [proto][verify] talker=" + talker
                                        + " content=" + content
                                        + " clientMsgId=" + cmid
                                        + " createTime=" + ct);
                            }

                            // 触发自动回复（异步）
                            AutoResponder.replyAsync(talker, content);
                        }

                        // 出向的 Hb(true,true) 也保存一下模板，便于后续复用
                        if (a && b && g8 != null) {
                            WechatG8Prototype.saveTemplate(g8);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(MainHook.TAG + " [trace][Hb.before] ex=" + t);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    logAfter(param);
                    try {
                        long ret = toLong(param.getResult());
                        if (ret > 0) {
                            Object g8 = param.args[0];
                            if (g8 != null) {
                                WechatG8Prototype.saveTemplate(g8);
                                XposedBridge.log(MainHook.TAG + " [proto] template saved: "
                                        + g8 + " g8CL@" + System.identityHashCode(g8.getClass()));
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(MainHook.TAG + " [trace][Hb.after] ex=" + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [trace] hook fail Hb: " + t);
        }
    }
    // endregion

    // region hook tb
    private static void hookTb(Class<?> i8Cls, Class<?> g8Param) {
        try {
            XposedHelpers.findAndHookMethod(i8Cls, "tb", g8Param, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    logBefore(param);
                    try {
                        Object g8 = param.args[0];
                        if (g8 != null) {
                            // tb 一般也是入向路径的一部分，这里也保存一遍模板兜底
                            WechatG8Prototype.saveTemplate(g8);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(MainHook.TAG + " [trace][tb.before] ex=" + t);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    logAfter(param);
                    try {
                        long ret = toLong(param.getResult());
                        if (ret > 0 && param.args != null && param.args.length > 0) {
                            Object g8 = param.args[0];
                            if (g8 != null) {
                                WechatG8Prototype.saveTemplate(g8);
                                XposedBridge.log(MainHook.TAG + " [proto] template saved: "
                                        + g8 + " g8CL@" + System.identityHashCode(g8.getClass()));
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(MainHook.TAG + " [trace][tb.after] ex=" + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [trace] hook fail tb: " + t);
        }
    }
    // endregion

    // region hook Ic
    private static void hookIc(Class<?> i8Cls, Class<?> g8Param) {
        try {
            XposedHelpers.findAndHookMethod(i8Cls, "Ic", long.class, g8Param, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    logBefore(param);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    logAfter(param);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [trace] hook fail Ic: " + t);
        }
    }
    // endregion

    // region helpers: logging & field access
    private static void logBefore(XC_MethodHook.MethodHookParam param) {
        try {
            Member m = param.method;
            String owner = (m != null && m.getDeclaringClass() != null)
                    ? m.getDeclaringClass().getName() : "?";
            String name = (m != null) ? m.getName() : "?";
            XposedBridge.log(MainHook.TAG + " [trace] BEFORE " + owner + "." + name
                    + " args=" + safeArgs(param.args));
        } catch (Throwable ignored) {}
    }

    private static void logAfter(XC_MethodHook.MethodHookParam param) {
        try {
            Member m = param.method;
            String owner = (m != null && m.getDeclaringClass() != null)
                    ? m.getDeclaringClass().getName() : "?";
            String name = (m != null) ? m.getName() : "?";
            XposedBridge.log(MainHook.TAG + " [trace] AFTER  " + owner + "." + name
                    + " ret=" + String.valueOf(param.getResult()));
        } catch (Throwable ignored) {}
    }

    private static String safeArgs(Object[] args) {
        if (args == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (i > 0) sb.append(", ");
            if (a == null) {
                sb.append("null");
            } else {
                String s = a.toString();
                // 避免一次性把 g8 全字段 dump 出来，截断一下
                if (s.length() > 120) s = s.substring(0, 120) + "...";
                sb.append(s);
            }
        }
        return sb.append("]").toString();
    }

    private static boolean toBool(Object o) {
        return (o instanceof Boolean) ? (Boolean) o : false;
    }

    private static long toLong(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Integer) return ((Integer) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Throwable ignored) {}
        return 0L;
    }

    private static String getStr(Object obj, String... names) {
        for (String n : names) {
            try {
                Object v = XposedHelpers.getObjectField(obj, n);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Long getLong(Object obj, String... names) {
        for (String n : names) {
            try {
                Object v = XposedHelpers.getObjectField(obj, n);
                if (v instanceof Long) return (Long) v;
                if (v instanceof Integer) return ((Integer) v).longValue();
                if (v != null) return Long.parseLong(String.valueOf(v));
            } catch (Throwable ignored) {}
        }
        return null;
    }
    // endregion
}

