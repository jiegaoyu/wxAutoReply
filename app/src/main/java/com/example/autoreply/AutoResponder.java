package com.example.autoreply;

import android.text.TextUtils;

import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

public class AutoResponder {

    private static final ConcurrentHashMap<String, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 3000;

    private static final ThreadLocal<Boolean> SENDING = new ThreadLocal<>();

    private static boolean isSending() { return Boolean.TRUE.equals(SENDING.get()); }

    public static void replyAsync(final String talker, final String text) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(text)) {
            XposedBridge.log(MainHook.TAG + " [reply] skip empty talker/text");
            return;
        }
        long now = System.currentTimeMillis();
        String key = talker + "|" + text;
        Long prev = LAST_SENT.get(key);
        if (prev != null && (now - prev) < DEDUP_WINDOW_MS) {
            XposedBridge.log(MainHook.TAG + " [dedup] skip duplicate send " + key);
            return;
        }
        LAST_SENT.put(key, now);

        new Thread(() -> replyOnce(talker, text)).start();
    }

    private static void replyOnce(String talker, String text) {
        if (isSending()) return;
        SENDING.set(true);
        try {
            Object sender = MainHook.sKernelMsgSender;
            if (sender == null || MainHook.sKernelMsgEntityCls == null) {
                XposedBridge.log(MainHook.TAG + " [reply] sender/entity not ready");
                return;
            }

            // 1) 模板克隆
            final Object g8 = WechatG8Prototype.cloneAndPatch(talker, text);
            if (g8 == null) { 
            
            XposedBridge.log(MainHook.TAG + " [send:A] no template g8; skip send"); return; }
            
            //WechatG8Prototype.dumpForDebug(g8);

            // 2) 先 Hb 拿序号（必须主线程）
            final long[] seqBox = { -1 };
            MainHook.runOnMainSync(() -> {
            //WechatG8Prototype.dumpForDebug(g8);
                long seq = WechatKernelSender.callHbAndGetSeq(sender, g8, true, true);
                seqBox[0] = seq;
            });
            long seq = seqBox[0];
            if (seq <= 0) return;

            // 3) 延迟 120~240ms，避免与模板完全重合
            try { Thread.sleep(120 + (long)(Math.random()*120)); } catch (Throwable ignored) {}

            // 4) Ic 出队（主线程）
            final int[] icBox = { -1 };
            MainHook.runOnMainSync(() -> {
                int r = WechatKernelSender.callIc(sender, seq, g8, true);
                icBox[0] = r;
                WechatKernelSender.pokeNotify(sender); // 关键：立刻唤醒队列，解决“要回桌面才发送”的问题
            });

            if (icBox[0] <= 0) {
                WechatKernelSender.tryU9OrRcOnce(sender, g8);
            }

            XposedBridge.log(MainHook.TAG + " [reply] -> " + talker + " ok=true path=A/Hb+Ic");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [reply] exception: " + t);
        } finally {
            SENDING.remove();
        }
    }
}

