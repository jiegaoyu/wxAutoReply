package com.example.autoreply;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatKernelSender {

    public static long callHbAndGetSeq(Object sender, Object g8, boolean a, boolean b) {
        try {
            return (long) XposedHelpers.callMethod(sender, "Hb", g8, a, b);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " callHb error: " + t);
            return -1;
        }
    }

    public static int callIc(Object sender, long seq, Object g8, boolean flag) {
        try {
            return (int) XposedHelpers.callMethod(sender, "Ic", seq, g8, flag);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " callIc error: " + t);
            return -1;
        }
    }

    /** Hb 之后兜底打一枪：不同版本有 U9 或 Rc */
    public static int tryU9OrRcOnce(Object sender, Object g8) {
        try { return (int) XposedHelpers.callMethod(sender, "U9", g8); }
        catch (Throwable ignored) {}
        try { return (int) XposedHelpers.callMethod(sender, "Rc", g8); }
        catch (Throwable ignored) {}
        return -1;
    }
}

