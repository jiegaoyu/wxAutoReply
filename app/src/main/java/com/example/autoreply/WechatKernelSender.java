package com.example.autoreply;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class WechatKernelSender {

    private WechatKernelSender() {}

    /** 是否已学到 sender 实例与 g8 类 */
    public static boolean isReady() {
        boolean ok = (SenderLearningHook.sSenderInstance != null && SenderLearningHook.sG8Class != null);
        XposedBridge.log(MainHook.TAG + " [ready?] sender=" + SenderLearningHook.sSenderInstance + " g8=" + SenderLearningHook.sG8Class);
        return ok;
    }

    /** 反射调用 Hb(g8, boolean, boolean)，返回 seq */
    public static long callHbAndGetSeq(Object sender, Object g8, boolean a, boolean b) {
        try {
            Class<?> i8Cls = sender.getClass();
            Method m = XposedHelpers.findMethodExactIfExists(i8Cls, "Hb",
                    SenderLearningHook.sG8Class, boolean.class, boolean.class);
            if (m == null) {
                // 兜底：按照名称与参数个数遍历找
                for (Method mm : i8Cls.getDeclaredMethods()) {
                    if (!"Hb".equals(mm.getName())) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 3 && ps[1] == boolean.class && ps[2] == boolean.class) {
                        mm.setAccessible(true);
                        Object r = mm.invoke(sender, g8, a, b);
                        return (r instanceof Number) ? ((Number) r).longValue() : 0L;
                    }
                }
                return 0L;
            }
            Object ret = m.invoke(sender, g8, a, b);
            return (ret instanceof Number) ? ((Number) ret).longValue() : 0L;
        } catch (Throwable e) {
            XposedBridge.log(MainHook.TAG + " [Hb.invoke] error: " + e);
            return 0L;
        }
    }

    /** 反射调用 Ic(long, g8, boolean) */
    public static int callIc(Object sender, long seq, Object g8, boolean flag) {
        try {
            Class<?> i8Cls = sender.getClass();
            Method m = XposedHelpers.findMethodExactIfExists(i8Cls, "Ic",
                    long.class, SenderLearningHook.sG8Class, boolean.class);
            if (m == null) {
                for (Method mm : i8Cls.getDeclaredMethods()) {
                    if (!"Ic".equals(mm.getName())) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 3 && ps[0] == long.class && ps[2] == boolean.class) {
                        mm.setAccessible(true);
                        Object r = mm.invoke(sender, seq, g8, flag);
                        return (r instanceof Number) ? ((Number) r).intValue() : 0;
                    }
                }
                return 0;
            }
            Object ret = m.invoke(sender, seq, g8, flag);
            return (ret instanceof Number) ? ((Number) ret).intValue() : 0;
        } catch (Throwable e) {
            XposedBridge.log(MainHook.TAG + " [Ic.invoke] error: " + e);
            return 0;
        }
    }

    /** 兜底打一枪：U9(g8) 或 Rc(g8) */
    public static int tryU9OrRcOnce(Object sender, Object g8) {
        try {
            Method u9 = XposedHelpers.findMethodExactIfExists(sender.getClass(), "U9", SenderLearningHook.sG8Class);
            if (u9 != null) {
                Object r = u9.invoke(sender, g8);
                return (r instanceof Number) ? ((Number) r).intValue() : 0;
            }
        } catch (Throwable ignored) {}
        try {
            Method rc = XposedHelpers.findMethodExactIfExists(sender.getClass(), "Rc", SenderLearningHook.sG8Class);
            if (rc != null) {
                Object r = rc.invoke(sender, g8);
                return (r instanceof Number) ? ((Number) r).intValue() : 0;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 尝试唤醒/刷新：Mc() / doNotify() / notify... / flush... */
    public static void pokeNotify(Object sender) {
        try {
            List<String> candidates = new ArrayList<>();
            candidates.add("Mc");
            candidates.add("doNotify");
            candidates.add("notifyChanged");
            candidates.add("notifyDataSetChanged");
            candidates.add("flush");
            boolean called = false;

            for (String name : candidates) {
                try {
                    Method m = XposedHelpers.findMethodExactIfExists(sender.getClass(), name);
                    if (m != null && m.getParameterTypes().length == 0 && m.getReturnType() == void.class) {
                        m.setAccessible(true);
                        m.invoke(sender);
                        XposedBridge.log(MainHook.TAG + " [poke] sender." + name + "()");
                        called = true;
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            if (!called) {
                // 枚举所有无参 void 方法，找包含 notify/flush 关键字的
                Method[] ms = sender.getClass().getDeclaredMethods();
                StringBuilder sb = new StringBuilder();
                for (Method m : ms) {
                    if (m.getParameterTypes().length == 0 && m.getReturnType() == void.class) {
                        sb.append(m.getName()).append("(),");
                        String low = m.getName().toLowerCase();
                        if (low.contains("notify") || low.contains("flush")) {
                            try {
                                m.setAccessible(true);
                                m.invoke(sender);
                                XposedBridge.log(MainHook.TAG + " [poke] invoked zero-arg notifier-like: " + m.getName() + "()");
                                called = true;
                                break;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                if (!called) {
                    XposedBridge.log(MainHook.TAG + " [poke] no notifier-like method matched");
                    XposedBridge.log(MainHook.TAG + " [poke] zero-arg void methods on " + sender.getClass().getName() + ": " + sb);
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(MainHook.TAG + " [poke] error: " + e);
        }
    }
}

