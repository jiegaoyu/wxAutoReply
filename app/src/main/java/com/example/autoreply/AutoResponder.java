package com.example.autoreply;

import android.text.TextUtils;

import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

public final class AutoResponder {

    private AutoResponder() {}

    private static final ConcurrentHashMap<String, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 3000; // 3s 去重窗口

    /** 对外统一入口（异步） */
    public static void replyAsync(final String talker, final String text) {
        long now = System.currentTimeMillis();
        String key = talker + "|" + text;
        Long prev = LAST_SENT.get(key);
        if (prev != null && (now - prev) < DEDUP_WINDOW_MS) {
            XposedBridge.log(MainHook.TAG + " [dedup] skip duplicate send " + key);
            return;
        }
        LAST_SENT.put(key, now);

        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(text)) {
            XposedBridge.log(MainHook.TAG + " [reply] skip empty talker/text");
            return;
        }
        new Thread(() -> reply(talker, text)).start();
    }

    /** 核心发送：模板克隆 + Hb + Ic + 兜底 + 唤醒 */
    private static void reply(String talker, String text) {
        try {
            if (!WechatKernelSender.isReady()) {
                XposedBridge.log(MainHook.TAG + " [reply] sender/entity not ready");
                return;
            }

            Object sender = SenderLearningHook.sSenderInstance;
            Object g8 = WechatG8Prototype.cloneAndPatch(talker, text);
            if (g8 == null) {
                XposedBridge.log(MainHook.TAG + " [send:A] no template g8; skip send");
                return;
            }

            // 1) 入队：Hb -> seq
            long seq = WechatKernelSender.callHbAndGetSeq(sender, g8, true, true);
            XposedBridge.log(MainHook.TAG + " [send:A] Hb ret=" + seq);
            if (seq <= 0) return;

            // 2) 轻微延迟，避免时间戳重合
            try { Thread.sleep(120 + (long) (Math.random() * 120)); } catch (Throwable ignored) {}

            // 3) 出队：Ic
            int rc = WechatKernelSender.callIc(sender, seq, g8, true);
            XposedBridge.log(MainHook.TAG + " [send:A] Ic ret=" + rc);

            if (rc <= 0) {
                int alt = WechatKernelSender.tryU9OrRcOnce(sender, g8);
                XposedBridge.log(MainHook.TAG + " [send:A] Ic<=0, fallback ret=" + alt);
            }

            // 4) 唤醒刷新（解决“要回桌面再回来才发出”的症状）
            WechatKernelSender.pokeNotify(sender);

            XposedBridge.log(MainHook.TAG + " [reply] -> " + talker + " ok=true path=A/Hb+Ic");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [reply] exception: " + t);
        }
    }
}

