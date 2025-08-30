package com.example.autoreply;

import android.text.TextUtils;

import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

public class AutoResponder {

    private static final ConcurrentHashMap<String, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 3000; // 3s 去重窗口

    /** 入口：异步防抖 */
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

        new Thread(() -> reply(talker, text), "wxAutoReply-Send").start();
    }

    /** 核心流程：模板克隆 -> Hb(主线程) -> 延迟 -> Ic(主线程) -> 唤醒 */
    private static void reply(String talker, String text) {
        try {
            Object sender = MainHook.sKernelMsgSender;
            if (sender == null) {
                XposedBridge.log(MainHook.TAG + " [reply] sender/entity not ready");
                return;
            }

            // 1) 模板克隆并修补字段
            Object g8 = WechatG8Prototype.cloneAndPatch(talker, text);
            if (g8 == null) {
                XposedBridge.log(MainHook.TAG + " [send:A] no template g8; skip send");
                return;
            }

            final long[] seqBox = { -1L };
            // 2) Hb 必须放主线程
            UiThread.runSync(() -> {
                long seq = WechatKernelSender.callHbAndGetSeq(sender, g8, true, true);
                seqBox[0] = seq;
            });
            long seq = seqBox[0];
            if (seq <= 0) return;

            // 3) 轻微延迟，避免时间戳完全重合
            try { Thread.sleep(120 + (long) (Math.random() * 120)); } catch (Throwable ignored) {}

            final int[] icBox = { 0 };
            // 4) Ic 也放主线程
            UiThread.runSync(() -> {
                int rc = WechatKernelSender.callIc(sender, seq, g8, true);
                icBox[0] = rc;
            });
            if (icBox[0] <= 0) {
                int alt = WechatKernelSender.tryU9OrRcOnce(sender, g8);
                XposedBridge.log(MainHook.TAG + " [send:A] Ic<=0, fallback ret=" + alt);
            }

            // 5) 关键：主动“唤醒”一次队列（你的设备上优先 Mc()）
            UiThread.post(() -> WechatKernelSender.pokeNotify(sender));

            XposedBridge.log(MainHook.TAG + " [reply] -> " + talker + " ok=true path=A/Hb+Ic");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [reply] exception: " + t);
        }
    }
}

