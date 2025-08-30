package com.example.autoreply;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class WechatG8Prototype {
    private static volatile Object TEMPLATE_G8 = null;
    private static volatile Class<?> G8_CLASS = null;

    private WechatG8Prototype() {}

    /** 在 Hb/tb 钩子里保存模板（你仓库已有调用点，不用改） */
    public static void saveTemplate(Object g8) {
        if (g8 == null) return;
        TEMPLATE_G8 = g8;
        if (G8_CLASS == null) G8_CLASS = g8.getClass();
        XposedBridge.log(MainHook.TAG + " [proto] template saved: " + g8 + " g8CL@" + System.identityHashCode(G8_CLASS));
    }

    /** 克隆一份模板并覆盖 talker/content/clientMsgId/createTime/isSend/status */
    public static Object cloneAndPatch(String talker, String text) {
        final Object proto = TEMPLATE_G8;
        if (proto == null) {
            XposedBridge.log(MainHook.TAG + " [proto] no template g8 yet");
            return null;
        }
        try {
            // 1) 先尝试 g8.clone()
            Object newG8 = null;
            try {
                newG8 = XposedHelpers.callMethod(proto, "clone");
            } catch (Throwable ignored) {
                // 2) 没有 clone 就 new 一个同类实例
                try {
                    if (G8_CLASS == null) G8_CLASS = proto.getClass();
                    newG8 = XposedHelpers.newInstance(G8_CLASS);
                    // 粗暴复制字段
                    Field[] fs = proto.getClass().getDeclaredFields();
                    for (Field f : fs) {
                        f.setAccessible(true);
                        Object v = f.get(proto);
                        try { f.set(newG8, v); } catch (Throwable ignored2) {}
                    }
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + " [proto] new/clone g8 failed: " + t);
                    return null;
                }
            }

            long now = System.currentTimeMillis();
            String cmid = "wxautoreply_" + System.nanoTime();

            // 覆盖关键字段
            try { XposedHelpers.setObjectField(newG8, "field_talker", talker); } catch (Throwable ignored) {
                try { XposedHelpers.setObjectField(newG8, "talker", talker); } catch (Throwable ignored2) {}
            }
            try { XposedHelpers.setObjectField(newG8, "field_content", text); } catch (Throwable ignored) {
                try { XposedHelpers.setObjectField(newG8, "content", text); } catch (Throwable ignored2) {}
            }
            try { XposedHelpers.setLongField(newG8, "field_createTime", now); } catch (Throwable ignored) {
                try { XposedHelpers.setLongField(newG8, "createTime", now); } catch (Throwable ignored2) {}
            }
            try { XposedHelpers.setObjectField(newG8, "field_clientMsgId", cmid); } catch (Throwable ignored) {
                try { XposedHelpers.setObjectField(newG8, "clientMsgId", cmid); } catch (Throwable ignored2) {}
            }
            // 方向 & 初始状态（发出中）
            try { XposedHelpers.setIntField(newG8, "field_isSend", 1); } catch (Throwable ignored) {
                try { XposedHelpers.setIntField(newG8, "isSend", 1); } catch (Throwable ignored2) {}
            }
            try { XposedHelpers.setIntField(newG8, "field_status", 1); } catch (Throwable ignored) {
                try { XposedHelpers.setIntField(newG8, "status", 1); } catch (Throwable ignored2) {}
            }

            XposedBridge.log(MainHook.TAG + " [proto][beforeSend] talker=" + talker
                    + " content=" + text + " clientMsgId=" + cmid + " createTime=" + now);
            return newG8;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [proto] cloneAndPatch exception: " + t);
            return null;
        }
    }
}

