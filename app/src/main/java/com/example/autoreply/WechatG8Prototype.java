package com.example.autoreply;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatG8Prototype {
    private static final String TAG = MainHook.TAG + " [proto]";
    private static volatile Object TEMPLATE_G8 = null;

    public static void saveTemplate(Object g8) {
        if (g8 == null) return;
        try {
            TEMPLATE_G8 = g8;
            if (MainHook.sKernelMsgEntityCls == null) {
                MainHook.sKernelMsgEntityCls = g8.getClass();
            }
            XposedBridge.log(TAG + " template saved: " + g8 + " g8CL@" + g8.getClass().getClassLoader().hashCode());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " saveTemplate err: " + t);
        }
    }

    public static Object cloneAndPatch(String talker, String text) {
        try {
            if (TEMPLATE_G8 == null || MainHook.sKernelMsgEntityCls == null) {
                XposedBridge.log(TAG + " no template g8 yet");
                return null;
            }
            Object newG8 = XposedHelpers.newInstance(MainHook.sKernelMsgEntityCls);

            // 先把模板上的字段复制过来
            Field[] fs = TEMPLATE_G8.getClass().getDeclaredFields();
            for (Field f : fs) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(TEMPLATE_G8);
                    f.set(newG8, v);
                } catch (Throwable ignored) {}
            }

            // 再覆盖少量关键字段
            try { XposedHelpers.setObjectField(newG8, "talker", talker); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(newG8, "field_talker", talker); } catch (Throwable ignored) {}

            try { XposedHelpers.setObjectField(newG8, "content", text); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(newG8, "field_content", text); } catch (Throwable ignored) {}

            long now = System.currentTimeMillis();
            try { XposedHelpers.setLongField(newG8, "createTime", now); } catch (Throwable ignored) {}
            try { XposedHelpers.setLongField(newG8, "field_createTime", now); } catch (Throwable ignored) {}

            try { XposedHelpers.setObjectField(newG8, "clientMsgId", "wxautoreply_" + now); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(newG8, "field_clientMsgId", "wxautoreply_" + now); } catch (Throwable ignored) {}

            XposedBridge.log(TAG + " [beforeSend] talker=" + readStr(newG8,"talker")
                    + " content=" + readStr(newG8,"content")
                    + " clientMsgId=" + readStr(newG8,"clientMsgId")
                    + " createTime=" + readLong(newG8,"createTime"));
dumpForDebug(newG8);

            return newG8;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " cloneAndPatch err: " + t);
            return null;
        }
    }

    private static String readStr(Object obj, String f) {
        try { Object v = XposedHelpers.getObjectField(obj, f); return v==null?null:String.valueOf(v); }
        catch (Throwable ignored) { return null; }
    }
    private static Long readLong(Object obj, String f) {
        try { return XposedHelpers.getLongField(obj, f); }
        catch (Throwable ignored) { return null; }
    }
    
    // 在文件顶部已有：package com.example.autoreply; 以及 XposedHelpers 的 import

public static void dumpForDebug(Object g8) {
    try {
        String talker = (String) tryGetAny(g8, "talker", "field_talker", "Z1");
        String content = (String) tryGetAny(g8, "content", "field_content", "a2");
        String clientMsgId = (String) tryGetAny(g8, "clientMsgId", "field_clientMsgId", "b2");
        Long createTime = (Long) tryGetAny(g8, "createTime", "field_createTime");
        XposedBridge.log(MainHook.TAG + " [proto][verify] talker=" + talker
                + " content=" + content
                + " clientMsgId=" + clientMsgId
                + " createTime=" + createTime);
    } catch (Throwable t) {
        XposedBridge.log(MainHook.TAG + " [proto][verify] dump error: " + t);
    }
}

// 这个工具方法放在本类里（若尚未存在）
private static Object tryGetAny(Object obj, String... names) {
    for (String n : names) {
        try { return XposedHelpers.getObjectField(obj, n); } catch (Throwable ignored) {}
        try { return XposedHelpers.callMethod(obj, "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1)); } catch (Throwable ignored) {}
    }
    return null;
}

}

