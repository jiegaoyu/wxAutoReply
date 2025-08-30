package com.example.autoreply;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatG8Prototype {

    private static volatile Object TEMPLATE_G8;

    public static void saveTemplate(Object g8) {
        if (g8 == null) return;
        TEMPLATE_G8 = g8;
        try {
            Class<?> cls = g8.getClass();
            XposedBridge.log(MainHook.TAG + " [proto] template saved: " + g8 + " g8CL@" + cls.hashCode());
        } catch (Throwable ignored) {}
    }

    public static Object cloneAndPatch(String talker, String text) {
        Object proto = TEMPLATE_G8;
        if (proto == null) {
            XposedBridge.log(MainHook.TAG + " [proto] no template g8 yet");
            return null;
        }
        try {
            Object g8 = XposedHelpers.callMethod(proto, "clone");

            // 兼容不同字段命名：优先 field_*，再回退裸字段
            setObj(g8, "field_talker", talker);
            setObj(g8, "talker", talker);

            setObj(g8, "field_content", text);
            setObj(g8, "content", text);

            // 自己发出
            setInt(g8, "field_isSend", 1);
            setInt(g8, "isSend", 1);

            // 发送中（Ic 后会被核心推进）
            setInt(g8, "field_status", 1);
            setInt(g8, "status", 1);

            // createTime 统一为毫秒；模板若是秒制/无效值则改为 nowMs
            long nowMs = System.currentTimeMillis();
            Long ct = firstLong(g8, "field_createTime", "createTime");
            if (ct == null || ct < 1_000_000_000_000L) ct = nowMs;
            long createTimeMs = Math.max(nowMs, ct) + 1; // +1 防止与模板完全一致导致排序并发问题
            setLong(g8, "field_createTime", createTimeMs);
            setLong(g8, "createTime", createTimeMs);

            // clientMsgId 唯一
            String cmid = "wxautoreply_" + System.nanoTime();
            setObj(g8, "field_clientMsgId", cmid);
            setObj(g8, "clientMsgId", cmid);

            // 关键：flag = (createTime<<32) | (isSend & 0x3)
            long isSendVal = 1;
            long flag = (createTimeMs << 32) | (isSendVal & 0x3);
            setLong(g8, "field_flag", flag);
            setLong(g8, "flag", flag);

            // 常见补充：文本类型
            setInt(g8, "type", 1);
            setInt(g8, "msgType", 1);
            setInt(g8, "msgSeq", 0);

            XposedBridge.log(MainHook.TAG + " [proto][beforeSend] talker=" + talker +
                    " content=" + text + " clientMsgId=" + cmid + " createTime=" + createTimeMs);
            logVerifyFields(g8);
            return g8;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [proto] cloneAndPatch ex: " + t);
            return null;
        }
    }

    // ========= helpers =========

    private static void setObj(Object o, String name, Object v) {
        try { XposedHelpers.setObjectField(o, name, v); } catch (Throwable ignored) {}
    }
    private static void setInt(Object o, String name, int v) {
        try { XposedHelpers.setIntField(o, name, v); } catch (Throwable ignored) {}
    }
    private static void setLong(Object o, String name, long v) {
        try { XposedHelpers.setLongField(o, name, v); } catch (Throwable ignored) {}
    }
    private static Long firstLong(Object o, String a, String b) {
        try { return XposedHelpers.getLongField(o, a); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(o, b); } catch (Throwable ignored) {}
        return null;
    }

    private static void logVerifyFields(Object g8) {
        try {
            int isSend = getIntOr(g8, "field_isSend", getIntOr(g8,"isSend",-1));
            int status = getIntOr(g8, "field_status", getIntOr(g8,"status",-1));
            long ct = getLongOr(g8, "field_createTime", getLongOr(g8,"createTime",-1));
            String cmid = getStrOr(g8, "field_clientMsgId", getStrOr(g8,"clientMsgId",null));
            XposedBridge.log(MainHook.TAG + " [proto][verify] isSend=" + isSend +
                    " status=" + status + " ct=" + ct + " cmid=" + cmid);
        } catch (Throwable ignored) {}
    }
    private static int getIntOr(Object o, String a, int dv){ try{return XposedHelpers.getIntField(o,a);}catch(Throwable t){return dv;}}
    private static long getLongOr(Object o, String a, long dv){ try{return XposedHelpers.getLongField(o,a);}catch(Throwable t){return dv;}}
    private static String getStrOr(Object o, String a, String dv){ try{return (String)XposedHelpers.getObjectField(o,a);}catch(Throwable t){return dv;}}
}

