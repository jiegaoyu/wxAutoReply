package com.example.autoreply;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;   // 用 XposedBridge 来 hookAll*
import de.robv.android.xposed.XposedHelpers;

public class WechatKernelTracer {

    // NOTE: 临时关闭去重/节流 => 把这两个变量保留，但先不生效（见 tryTriggerAutoReply）
    private static final Set<Long> sHandledSvrIds =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    private static final Map<String, Long> sLastReplyTs = new ConcurrentHashMap<>();
    private static final long REPLY_THROTTLE_MS = 2500L;

    public static void install(ClassLoader cl) {
        try {
            final Class<?> i8Cls = XposedHelpers.findClassIfExists("com.tencent.mm.storage.i8", cl);
            final Class<?> g8Cls = XposedHelpers.findClassIfExists("com.tencent.mm.storage.g8", cl);

            if (i8Cls == null || g8Cls == null) {
                MainHook.w("WechatKernelTracer install skipped: i8=" + i8Cls + " g8=" + g8Cls);
                return;
            }

            MainHook.setG8Class(g8Cls);

            // === Hb(g8, boolean, boolean)
            XposedBridge.hookAllMethods(i8Cls, "Hb", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object g8 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (g8 != null) {
                        WechatG8Prototype.saveTemplate(g8);
                        WechatG8Prototype.learnTalkerId(g8);
                        MainHook.log("[Hb.before] " + WechatG8Prototype.brief(g8));
                    }
                    if (param.thisObject != null && MainHook.sKernelMsgSender == null) {
                        MainHook.setSender(param.thisObject);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object g8 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (g8 != null) {
                        WechatG8Prototype.saveTemplate(g8);
                        WechatG8Prototype.learnTalkerId(g8);
                        MainHook.log("[Hb.after] " + WechatG8Prototype.brief(g8) +
                                " ret=" + String.valueOf(param.getResult()));
                    }
                }
            });

            // === Ic(rowId, g8, boolean)
            XposedBridge.hookAllMethods(i8Cls, "Ic", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    MainHook.log("[trace] BEFORE Ic args=" + argsToString(param.args));
                    if (param.thisObject != null && MainHook.sKernelMsgSender == null) {
                        MainHook.setSender(param.thisObject);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    MainHook.log("[trace] AFTER  Ic ret=" + String.valueOf(param.getResult()));
                }
            });

            // === tb(g8)
            XposedBridge.hookAllMethods(i8Cls, "tb", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object in = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (in != null) {
                        WechatG8Prototype.saveTemplate(in);
                        MainHook.log("[tb.before] in=" + WechatG8Prototype.brief(in) +
                                " args=" + argsToString(param.args));
                    }
                    if (param.thisObject != null && MainHook.sKernelMsgSender == null) {
                        MainHook.setSender(param.thisObject);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object in = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    MainHook.log("[tb.after] in=" + (in != null ? WechatG8Prototype.brief(in) : "null")
                            + " ret=" + String.valueOf(param.getResult()));
                    tryTriggerAutoReply(in);
                }
            });

            // === 构造器（更早拿到 sender）
            XposedBridge.hookAllConstructors(i8Cls, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && MainHook.sKernelMsgSender == null) {
                        MainHook.setSender(param.thisObject);
                    }
                }
            });

            MainHook.log("WechatKernelTracer installed.");
        } catch (Throwable t) {
            MainHook.e("WechatKernelTracer install failed", t);
        }
    }

    /* ============ 自动回复触发 ============ */
    private static void tryTriggerAutoReply(Object g8) {
        if (g8 == null) return;

        Integer isSend  = WechatG8Prototype.readIntBoxed(g8, "field_isSend");
        Integer type    = WechatG8Prototype.readIntBoxed(g8, "field_type");
        String  talker  = WechatG8Prototype.readString(g8, "field_talker");
        String  content = WechatG8Prototype.readString(g8, "field_content");
        Long    svrId   = WechatG8Prototype.readLongBoxed(g8, "field_msgSvrId");
        Integer status  = WechatG8Prototype.readIntBoxed(g8, "field_status");

        // 只处理 “收到的文本消息且 status==3(已入库/收取完成)” 的场景
        if (isSend == null || type == null || talker == null) return;
        if (isSend != 0 || type != 1) return;
        if (status != null && status != 3) return;

        // —— 临时关闭去重/节流以定位问题 —— //
        // if (svrId != null && svrId > 0 && !sHandledSvrIds.add(svrId)) return;
        // long now = System.currentTimeMillis();
        // Long last = sLastReplyTs.get(talker);
        // if (last != null && (now - last) < REPLY_THROTTLE_MS) return;
        // sLastReplyTs.put(talker, now);

        String reply = "自动回复: 已收到「" + (content == null ? "" : content) + "」";
        MainHook.log("[reply][verify] talker=" + talker + " content=" + reply);

        // 关键定位点：进入 send 前后都打日志
        MainHook.log("[reply] calling AutoResponder.send() …");
        AutoResponder.send(talker, reply);
        MainHook.log("[reply] AutoResponder.send() returned.");
    }

    /* ============ 小工具 ============ */
    private static String argsToString(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder(64);
        sb.append("[");
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (i > 0) sb.append(", ");
            if (a == null) { sb.append("null"); continue; }
            String s = a.toString();
            if (s.startsWith("com.tencent.mm.storage.g8@")) {
                sb.append("g8");
            } else {
                sb.append(s);
            }
        }
        sb.append("]");
        return sb.toString();
    }
}