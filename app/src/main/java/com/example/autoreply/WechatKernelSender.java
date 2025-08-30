package com.example.autoreply;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class WechatKernelSender {

    private WechatKernelSender() {}

    /** 供 SenderLearningHook / KernelSendTracer 调用：是否可发送 */
    public static boolean isReady() {
        boolean ok = (MainHook.sKernelMsgSender != null && MainHook.sKernelMsgEntityCls != null);
        if (!ok) {
            XposedBridge.log(MainHook.TAG + " [ready?] sender=" + MainHook.sKernelMsgSender
                    + " g8=" + MainHook.sKernelMsgEntityCls);
        }
        return ok;
    }

    /** Hb：返回 seq；异常时返回 <=0 */
    public static long callHbAndGetSeq(Object sender, Object g8, boolean a, boolean b) {
        try {
            Method hb = XposedHelpers.findMethodExactIfExists(
                    sender.getClass(), "Hb",
                    g8.getClass(), boolean.class, boolean.class);
            if (hb == null) {
                XposedBridge.log(MainHook.TAG + " [trace] Hb not found on " + sender.getClass());
                return -1;
            }
            Object ret = hb.invoke(sender, g8, a, b);
            long seq = (ret instanceof Number) ? ((Number) ret).longValue() : -1L;
            XposedBridge.log(MainHook.TAG + " [send:A] Hb ret=" + seq);
            return seq;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [send:A] Hb exception " + t);
            return -1;
        }
    }

    /** Ic：出队；>0 视为成功 */
    public static int callIc(Object sender, long seq, Object g8, boolean flag) {
        try {
            Method ic = XposedHelpers.findMethodExactIfExists(
                    sender.getClass(), "Ic",
                    long.class, g8.getClass(), boolean.class);
            if (ic == null) {
                XposedBridge.log(MainHook.TAG + " [trace] Ic not found on " + sender.getClass());
                return 0;
            }
            Object ret = ic.invoke(sender, seq, g8, flag);
            int rc = (ret instanceof Number) ? ((Number) ret).intValue() : 0;
            XposedBridge.log(MainHook.TAG + " [send:A] Ic ret=" + rc);
            return rc;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [send:A] Ic exception " + t);
            return 0;
        }
    }

    /** 兜底一次：U9 或 Rc 二选一打一枪 */
    public static int tryU9OrRcOnce(Object sender, Object g8) {
        for (String name : new String[]{"U9", "Rc"}) {
            try {
                Method m = XposedHelpers.findMethodExactIfExists(
                        sender.getClass(), name, g8.getClass());
                if (m != null) {
                    Object ret = m.invoke(sender, g8);
                    int rc = (ret instanceof Number) ? ((Number) ret).intValue() : 0;
                    if (rc > 0) return rc;
                }
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    /**
     * 立刻“唤醒/刷新”发送队列。
     * 先按常见名尝试；不命中则枚举零参 void 方法，优先 Mc()。
     */
    public static void pokeNotify(Object sender) {
        if (sender == null) return;

        // 1) 常见唤醒名
        ArrayList<String> common = new ArrayList<>(Arrays.asList(
                "doNotify", "notifyQueue", "notifyAllPending",
                "flush", "tick", "poke", "wake", "pulse",
                "Mc", "Nc", "Qc", "Rc0", "W9", "WA"
        ));
        for (String n : common) {
            try {
                Method m = XposedHelpers.findMethodExactIfExists(sender.getClass(), n);
                if (m != null && m.getParameterTypes().length == 0
                        && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    m.invoke(sender);
                    XposedBridge.log(MainHook.TAG + " [poke] sender." + n + "()");
                    return;
                }
            } catch (Throwable ignored) {}
        }

        // 2) 枚举零参 void 方法，排除明显无关
        try {
            Method[] ms = sender.getClass().getDeclaredMethods();
            Method candidate = null;
            for (Method m : ms) {
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() != void.class) continue;
                String nm = m.getName();
                if (Modifier.isStatic(m.getModifiers())) continue;
                if ("wait".equals(nm) || "notify".equals(nm) || "notifyAll".equals(nm)
                        || "lock".equals(nm) || "unlock".equals(nm)
                        || "finalize".equals(nm) || "toString".equals(nm)
                        || "hashCode".equals(nm) || "getClass".equals(nm)) {
                    continue;
                }
                if ("Mc".equals(nm)) { candidate = m; break; }
                if (candidate == null) candidate = m;
            }
            if (candidate != null) {
                candidate.setAccessible(true);
                candidate.invoke(sender);
                XposedBridge.log(MainHook.TAG + " [poke] sender." + candidate.getName() + "()");
            } else {
                XposedBridge.log(MainHook.TAG + " [poke] no notifier-like method matched");
            }
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [poke] enumerate failed: " + t);
        }
    }
}

