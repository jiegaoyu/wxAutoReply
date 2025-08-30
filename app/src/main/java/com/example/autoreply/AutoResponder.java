package com.example.autoreply;

import de.robv.android.xposed.XposedHelpers;

/**
 * 负责构造并通过内核 sender 发送文本消息
 */
public class AutoResponder {

    /**
     * 构造并发送一条文本消息到 talker。
     */
    public static void send(String talker, String replyText) {
        if (talker == null || talker.length() == 0 || replyText == null) {
            MainHook.w("[reply] invalid args talker=" + talker + " reply=" + replyText);
            return;
        }
        final Object   sender = MainHook.sKernelMsgSender;      // com.tencent.mm.storage.i8 实例
        final Class<?> g8Cls  = MainHook.sKernelMsgEntityCls;   // com.tencent.mm.storage.g8 Class

        MainHook.log("[reply] enter AutoResponder.send talker=" + talker + " reply=" + replyText);
        MainHook.log("[reply] state sender=" + sender + " g8Cls=" + g8Cls);

        if (sender == null || g8Cls == null) {
            MainHook.w("[reply] missing sender/g8Cls. sender=" + sender + " g8Cls=" + g8Cls);
            return;
        }

        try {
            MainHook.log("[reply] about to buildOutgoing …");
            // 1) 基于模板构建一条“已设置 isSend=1 / type=1 / content / to 等字段”的 g8
            Object g8 = WechatG8Prototype.buildOutgoing(talker, replyText);
            if (g8 == null) {
                MainHook.w("[reply] buildOutgoing returns null");
                return;
            }
            MainHook.log("[reply] buildOutgoing returned: " + g8);

            // 统一用 bestRowId 代表“当前生效的消息行”
            long bestRowId = 0L;

            // 2) 插入数据库：Hb(g8, true, true) -> rowId
            try {
                MainHook.log("[reply] calling Hb …");
                Object r = XposedHelpers.callMethod(sender, "Hb", g8, true, true);
                MainHook.log("[reply] Hb returned raw=" + r);
                long v = toLong(r);
                if (v > 0) bestRowId = v;
            } catch (Throwable t) {
                MainHook.e("[reply] call Hb failed", t);
            }
            MainHook.log("[reply] Hb rowId=" + bestRowId);

            // 3) 先 Ic 一次（用当前 bestRowId）
            try {
                MainHook.log("[reply] calling Ic with rowId=" + bestRowId + " …");
                Object retIc = XposedHelpers.callMethod(sender, "Ic", bestRowId, g8, true);
                MainHook.log("[reply] Ic ret=" + String.valueOf(retIc));
            } catch (Throwable t) {
                MainHook.e("[reply] call Ic failed", t);
            }

            // 4) 多重“派发”路径：Mb/U9 -> Ec/Lc -> I9 -> rc
            boolean clearSuccess = false;

            // 4.1 Mb(g8) -> long（常见“入队/发送一条”）
            try {
                Object ret = XposedHelpers.callMethod(sender, "Mb", g8);
                long v = toLong(ret);
                MainHook.log("[reply][dispatch] Mb(g8) ret=" + v);
                if (v > 0) {
                    clearSuccess = true;
                    if (v != bestRowId) {
                        MainHook.log("[reply][dispatch] update bestRowId: " + bestRowId + " -> " + v + " (from Mb)");
                        bestRowId = v;
                    }
                }
            } catch (Throwable t) {
                MainHook.e("[reply][dispatch] Mb(g8) failed", t);
            }

            // 4.2 U9(g8) -> long（你的日志里它会返回一个“新行号”，要以它为准）
            try {
                Object ret = XposedHelpers.callMethod(sender, "U9", g8);
                long v = toLong(ret);
                MainHook.log("[reply][dispatch] U9(g8) ret=" + v);
                if (v > 0) {
                    clearSuccess = true;
                    if (v != bestRowId) {
                        MainHook.log("[reply][dispatch] update bestRowId: " + bestRowId + " -> " + v + " (from U9)");
                        bestRowId = v;
                    }
                }
            } catch (Throwable t) {
                MainHook.e("[reply][dispatch] U9(g8) failed", t);
            }

            // 5) 在 Ec/Lc 前再 Ic 一次，用“最新 bestRowId”非常关键
            try {
                MainHook.log("[reply] re-Ic with rowId=" + bestRowId + " …");
                Object retIc2 = XposedHelpers.callMethod(sender, "Ic", bestRowId, g8, true);
                MainHook.log("[reply] re-Ic ret=" + String.valueOf(retIc2));
            } catch (Throwable t) {
                MainHook.e("[reply] re-Ic failed", t);
            }

            // 4.3 Ec(rowId, g8) -> int
            try {
                Object ret = XposedHelpers.callMethod(sender, "Ec", bestRowId, g8);
                int v = toInt(ret);
                MainHook.log("[reply][dispatch] Ec(rowId=" + bestRowId + ",g8) ret=" + v);
                if (v >= 0) clearSuccess = true; // 非负一般代表成功或已入队
            } catch (Throwable t) {
                MainHook.e("[reply][dispatch] Ec(rowId,g8) failed", t);
            }

            // 4.4 Lc(rowId, g8) -> void
            try {
                XposedHelpers.callMethod(sender, "Lc", bestRowId, g8);
                MainHook.log("[reply][dispatch] Lc(rowId=" + bestRowId + ",g8) ok");
            } catch (Throwable t) {
                MainHook.e("[reply][dispatch] Lc(rowId,g8) failed", t);
            }

            // 4.5 I9(g8) -> int
            try {
                Object ret = XposedHelpers.callMethod(sender, "I9", g8);
                int v = toInt(ret);
                MainHook.log("[reply][dispatch] I9(g8) ret=" + v);
                if (v > 0) clearSuccess = true;
            } catch (Throwable t) {
                MainHook.e("[reply][dispatch] I9(g8) failed", t);
            }

            // 4.6 rc(g8) -> void（尽力触发一次）
            try {
                XposedHelpers.callMethod(sender, "rc", g8);
                MainHook.log("[reply][dispatch] rc(g8) ok");
            } catch (Throwable t) {
                MainHook.e("[reply][dispatch] rc(g8) failed", t);
            }

            // 6) 最后 poke：Mc()
            try {
                XposedHelpers.callMethod(sender, "Mc");
                MainHook.log("[reply] poke: sender.Mc()");
            } catch (Throwable t) {
                MainHook.e("[reply] poke Mc failed", t);
            }

            MainHook.log("[reply][after] talker=" + talker + " content=" + replyText
                    + " isSend=1 type=1 status=1 clearSuccess=" + clearSuccess);
            MainHook.log("[reply] AutoResponder.send() returned.");

        } catch (Throwable t) {
            MainHook.e("[reply] unexpected", t);
        }
    }

    /* ---------------- 小工具：类型转换 ---------------- */

    private static int toInt(Object v) {
        try {
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Long)    return (int) ((Long) v).longValue();
            if (v != null)            return Integer.parseInt(String.valueOf(v));
        } catch (Throwable ignored) {}
        return -1;
    }

    private static long toLong(Object v) {
        try {
            if (v instanceof Long)    return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
            if (v != null)            return Long.parseLong(String.valueOf(v));
        } catch (Throwable ignored) {}
        return -1L;
    }
}