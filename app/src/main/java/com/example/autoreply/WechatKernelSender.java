package com.example.autoreply;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatKernelSender {
    private static final String TAG = MainHook.TAG;

    private static volatile Method M_Hb; // long Hb(g8, boolean, boolean)
    private static volatile Method M_Ic; // int  Ic(long, g8, boolean)
    private static volatile Method M_U9; // long U9(g8)
    private static volatile Method M_Rc; // int  Rc(g8)
    private static volatile Method M_doNotify; // void doNotify()

    // ========= 学习阶段 =========
    public static boolean learnSenderIfNull(Object sender) {
        if (sender == null) return false;
        if (MainHook.sKernelMsgSender == null) {
            MainHook.sKernelMsgSender = sender;
            return true;
        }
        return false;
    }

    public static void learnEntityClassIfNull(ClassLoader cl) {
        if (MainHook.sKernelMsgEntityCls != null) return;
        try {
            Class<?> g8 = cl.loadClass("com.tencent.mm.storage.g8");
            MainHook.sKernelMsgEntityCls = g8;
            XposedBridge.log(TAG + " [learn] g8 constructed, entity class set: " + g8.getName());
        } catch (Throwable ignored) {}
    }

    public static boolean isReady() {
        return MainHook.sKernelMsgSender != null && MainHook.sKernelMsgEntityCls != null;
    }

    private static void ensureMethods(Object sender) {
        if (sender == null) return;
        Class<?> sc = sender.getClass();
        try {
            if (M_Hb == null)
                M_Hb = XposedHelpers.findMethodExactIfExists(sc, "Hb",
                        MainHook.sKernelMsgEntityCls, boolean.class, boolean.class);
        } catch (Throwable ignored) {}
        try {
            if (M_Ic == null)
                M_Ic = XposedHelpers.findMethodExactIfExists(sc, "Ic",
                        long.class, MainHook.sKernelMsgEntityCls, boolean.class);
        } catch (Throwable ignored) {}
        try {
            if (M_U9 == null)
                M_U9 = XposedHelpers.findMethodExactIfExists(sc, "U9",
                        MainHook.sKernelMsgEntityCls);
        } catch (Throwable ignored) {}
        try {
            if (M_Rc == null)
                M_Rc = XposedHelpers.findMethodExactIfExists(sc, "Rc",
                        MainHook.sKernelMsgEntityCls);
        } catch (Throwable ignored) {}
        try {
            if (M_doNotify == null)
                M_doNotify = XposedHelpers.findMethodExactIfExists(sc, "doNotify");
        } catch (Throwable ignored) {}
    }

    // ========= 发送链路 =========
    public static long callHbAndGetSeq(Object sender, Object g8, boolean a, boolean b) {
        try {
            ensureMethods(sender);
            if (M_Hb == null) return -1;
            Object ret = M_Hb.invoke(sender, g8, a, b);
            long seq = (ret instanceof Number) ? ((Number) ret).longValue() : -1;
            return seq;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " callHb error: " + t);
            return -1;
        }
    }

    public static int callIc(Object sender, long seq, Object g8, boolean flag) {
        try {
            ensureMethods(sender);
            if (M_Ic == null) return 0;
            Object ret = M_Ic.invoke(sender, seq, g8, flag);
            return (ret instanceof Number) ? ((Number) ret).intValue() : 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " callIc error: " + t);
            return 0;
        }
    }

    public static int tryU9OrRcOnce(Object sender, Object g8) {
        ensureMethods(sender);
        try {
            if (M_U9 != null) {
                Object r = M_U9.invoke(sender, g8);
                return (r instanceof Number) ? ((Number) r).intValue() : 0;
            }
        } catch (Throwable ignored) {}
        try {
            if (M_Rc != null) {
                Object r = M_Rc.invoke(sender, g8);
                return (r instanceof Number) ? ((Number) r).intValue() : 0;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    public static void pokeNotify(Object sender) {
        try {
            ensureMethods(sender);
            if (M_doNotify != null) {
                M_doNotify.invoke(sender);
                XposedBridge.log(TAG + " [poke] sender.doNotify()");
            }
        } catch (Throwable ignored) {}
    }
}

