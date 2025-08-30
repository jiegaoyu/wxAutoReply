package com.example.autoreply;

import android.content.Context;

import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatMsgDbHook {

    private static final ConcurrentHashMap<String, Long> LAST_IN = new ConcurrentHashMap<>();
    private static final long IN_DEDUP_MS = 1500;

    public static void install(Context ctx, ClassLoader cl) {
        try {
            // 入库/出库常见类（不同版本名不同，采用宽松 hook）
            final Class<?> storageClass = XposedHelpers.findClassIfExists("com.tencent.mm.storage.i8", cl);
            final Class<?> msgClass     = XposedHelpers.findClassIfExists("com.tencent.mm.storage.g8", cl);
            if (storageClass == null || msgClass == null) {
                XposedBridge.log(MainHook.TAG + " WechatMsgDbHook miss classes");
                return;
            }

            // 监听 Hb(g8,false,true) —— 收到消息
            XposedHelpers.findAndHookMethod(storageClass, "Hb", msgClass, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        boolean a = (Boolean) param.args[1];
                        boolean b = (Boolean) param.args[2];
                        if (a) return; // a=true 通常是发送流程
                        Object g8 = param.args[0];

                        String talker = getString(g8, "talker", "field_talker");
                        String content = getString(g8, "content", "field_content");

                        if (talker == null || content == null) return;

                        // 入库去重：同一个 talker+content 在短时间内仅触发一次
                        long now = System.currentTimeMillis();
                        String key = talker + "|" + content;
                        Long prev = LAST_IN.get(key);
                        if (prev != null && (now - prev) < IN_DEDUP_MS) return;
                        LAST_IN.put(key, now);

                        XposedBridge.log(MainHook.TAG + " [MSG:IN] talker=" + talker + " len=" + content.length());
                        AutoResponder.replyAsync(talker, content); // 触发自动回复
                    } catch (Throwable ignored) {}
                }
            });

        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " WechatMsgDbHook install error: " + t);
        }
    }

    private static String getString(Object obj, String... names) {
        for (String n : names) {
            try {
                Object v = XposedHelpers.getObjectField(obj, n);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}

