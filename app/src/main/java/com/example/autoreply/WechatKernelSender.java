package com.example.autoreply;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatKernelSender {
    private static final String TAG = MainHook.TAG;

    /** 发送前置：是否已学到 sender / g8 */
    public static boolean isReady() {
        return MainHook.sKernelMsgSender != null && MainHook.sKernelMsgEntityCls != null;
    }

    /** 从 i8 实例里“学习” sender（通常在 Hb/tb 被 hook 到时调用） */
    public static boolean learnSenderIfNull(Object maybeSender) {
        if (maybeSender == null) return false;
        if (MainHook.sKernelMsgSender == null) {
            MainHook.sKernelMsgSender = maybeSender;
            XposedBridge.log(TAG + " [learn] sender instance learned: " + maybeSender.getClass().getName());
            return true;
        }
        return false;
    }

    /** 从 CL 里“学习” g8 类 */
    public static void learnEntityClassIfNull(ClassLoader cl) {
        if (MainHook.sKernelMsgEntityCls == null) {
            try {
                Class<?> g8 = XposedHelpers.findClassIfExists("com.tencent.mm.storage.g8", cl);
                if (g8 != null) {
                    MainHook.sKernelMsgEntityCls = g8;
                    XposedBridge.log(TAG + " [learn] g8 class: " + g8.getName());
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 直接调用 Hb(g8, boolean, boolean)，返回 seq，>0 表示入队成功 */
    public static long callHbAndGetSeq(Object sender, Object g8, boolean a, boolean b) {
        if (sender == null || g8 == null) return -1;
        try {
            Object ret = XposedHelpers.callMethod(sender, "Hb", g8, a, b);
            long seq = castLong(ret);
            XposedBridge.log(TAG + " [send:A] Hb ret=" + seq);
            return seq;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [send:A] Hb exception: " + t);
            return -1;
        }
    }

    /** 调用 Ic(seq, g8, true) 保障出队 */
    public static int callIc(Object sender, long seq, Object g8, boolean commit) {
        if (sender == null || g8 == null || seq <= 0) return 0;
        try {
            Object ret = XposedHelpers.callMethod(sender, "Ic", seq, g8, commit);
            int rc = castInt(ret);
            XposedBridge.log(TAG + " [send:A] Ic ret=" + rc);
            return rc;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [send:A] Ic exception: " + t);
            return 0;
        }
    }

    /** Ic 失败时兜底打一枪（U9 或 Rc 其一） */
    public static int tryU9OrRcOnce(Object sender, Object g8) {
        if (sender == null || g8 == null) return 0;
        // 优先 U9
        try {
            Object ret = XposedHelpers.callMethod(sender, "U9", g8);
            int rc = castInt(ret);
            if (rc != 0) return rc;
        } catch (Throwable ignored) {}
        // 再试 Rc
        try {
            Object ret = XposedHelpers.callMethod(sender, "Rc", g8);
            return castInt(ret);
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 解决“回到桌面再回来才发出”的关键：主动唤醒 sender 的通知 */
    public static void pokeUi(Object sender) {
        if (sender == null) return;
        try {
            // 微信内核里常见的方法名是 doNotify / notifyDataSetChanged 之类，这里用 doNotify。
            XposedHelpers.callMethod(sender, "doNotify");
            XposedBridge.log(TAG + " [poke] sender.doNotify()");
        } catch (Throwable t) {
            // 容错：有些版本方法名或者可见性不同，静默忽略
            XposedBridge.log(TAG + " [poke] doNotify() not available: " + t);
        }
    }

    /** 兼容 AutoResponder 里已有的调用名 */
    public static void pokeNotify(Object sender) {
        pokeUi(sender);
    }

    // —— 工具方法 ——
    private static long castLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Long) return (Long) o;
        if (o instanceof Integer) return ((Integer) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Throwable ignored) {}
        return 0;
    }

    private static int castInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Long) {
            long v = (Long) o;
            if (v > Integer.MAX_VALUE) return 1; // 只关心“是否非零”
            return (int) v;
        }
        try { return Integer.parseInt(String.valueOf(o)); } catch (Throwable ignored) {}
        return 0;
    }
}

