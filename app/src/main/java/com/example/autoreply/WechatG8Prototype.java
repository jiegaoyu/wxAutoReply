package com.example.autoreply;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatG8Prototype {
    private static final String TAG = MainHook.TAG;

    // 缓存的模板 g8 和它的 Class
    private static volatile Object sTemplate;
    private static volatile Class<?> sG8Class;
    private static volatile int sTemplateClHash;

    /** 在 trace 的 BEFORE/AFTER 中调用一次，缓存“被内核认可”的 g8 模板 */
    public static void saveTemplate(Object g8) {
        if (g8 == null) return;
        try {
            if (sTemplate == null) {
                sTemplate = g8;
                sG8Class  = g8.getClass();
                ClassLoader cl = sG8Class.getClassLoader();
                sTemplateClHash = (cl == null ? 0 : System.identityHashCode(cl));
                XposedBridge.log(TAG + " [proto] template saved: " + g8 +
                        " g8CL@" + sTemplateClHash);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [proto] saveTemplate err " + t);
        }
    }

    /** 需要时可读取模板是否已就绪 */
    public static boolean hasTemplate() {
        return sTemplate != null;
    }

    /** 克隆并打补丁：仅修改 talker/content/createTime/clientMsgId + 关键字段清零 */
    public static Object cloneAndPatch(String talker, String text) {
        if (sTemplate == null) {
            XposedBridge.log(TAG + " [proto] no template g8 yet");
            return null;
        }
        Object proto = sTemplate;
        try {
            Object newG8 = XposedHelpers.callMethod(proto, "clone");

            // 1) 必填四件
            safeSetObject(newG8, "talker", talker);
            safeSetObject(newG8, "field_talker", talker);

            safeSetObject(newG8, "content", text);
            safeSetObject(newG8, "field_content", text);

            long now = System.currentTimeMillis();
            safeSetLong(newG8, "createTime", now);
            safeSetLong(newG8, "field_createTime", now);

            String cmid = "wxautoreply_" + now + "_" + System.nanoTime();
            safeSetObject(newG8, "clientMsgId", cmid);
            safeSetObject(newG8, "field_clientMsgId", cmid);

            // 2) 关键清零/重置 —— 防止卡队列/被判重/错误状态
            safeSetLong(newG8, "msgId", 0L);
            safeSetLong(newG8, "field_msgId", 0L);
            safeSetLong(newG8, "msgSvrId", 0L);
            safeSetLong(newG8, "field_msgSvrId", 0L);
            safeSetLong(newG8, "msgSeq", 0L);
            safeSetLong(newG8, "seq", 0L);
            safeSetLong(newG8, "flag", 0L);

            // 发送态：多数版本 1 / 2 为“发送中”，任选其一；可按你机型换成 2 试试
            safeSetInt(newG8, "status", 1);
            safeSetInt(newG8, "field_status", 1);
            safeSetInt(newG8, "isSend", 1);
            safeSetInt(newG8, "field_isSend", 1);

            // 其他容易导致复用/异常的“痕迹字段”
            safeSetObject(newG8, "compressContent", null);
            safeSetObject(newG8, "reserved", null);
            safeSetObject(newG8, "source", null);
            safeSetObject(newG8, "transContent", null);
            safeSetObject(newG8, "bizClientMsgId", null);
            safeSetObject(newG8, "imgPath", null);
            safeSetLong(newG8, "talkerId", 0L);

            // 3) 最后打印关键字段，便于确认
            dumpBeforeSend(newG8);
            return newG8;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [proto] cloneAndPatch err " + t);
            return null;
        }
    }

    private static void dumpBeforeSend(Object g8) {
        try {
            Object talker  = tryGetObject(g8, "talker", tryGetObject(g8, "field_talker", null));
            Object content = tryGetObject(g8, "content", tryGetObject(g8, "field_content", null));
            Object cmid    = tryGetObject(g8, "clientMsgId", tryGetObject(g8, "field_clientMsgId", null));
            Long  ts       = tryGetLong(g8, "createTime", tryGetLong(g8, "field_createTime", null));
            XposedBridge.log(TAG + " [proto][beforeSend] talker=" + talker +
                    " content=" + content + " clientMsgId=" + cmid + " createTime=" + ts);
        } catch (Throwable ignored) {}
    }

    // ---------- 安全 set/get ----------

    private static void safeSetLong(Object obj, String name, long v) {
        try { XposedHelpers.setLongField(obj, name, v); } catch (Throwable ignored) {}
    }
    private static void safeSetInt(Object obj, String name, int v) {
        try { XposedHelpers.setIntField(obj, name, v); } catch (Throwable ignored) {}
    }
    private static void safeSetObject(Object obj, String name, Object v) {
        try { XposedHelpers.setObjectField(obj, name, v); } catch (Throwable ignored) {}
    }
    private static Object tryGetObject(Object obj, String name, Object def) {
        try { return XposedHelpers.getObjectField(obj, name); } catch (Throwable ignored) { return def; }
    }
    private static Long tryGetLong(Object obj, String name, Long def) {
        try { return XposedHelpers.getLongField(obj, name); } catch (Throwable ignored) { return def; }
    }

    // 供其他类需要时获知 g8 类
    public static Class<?> getG8Class() { return sG8Class; }
}

