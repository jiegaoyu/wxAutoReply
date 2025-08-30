package com.example.autoreply;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AutoResponder {

    private static final ConcurrentHashMap<String, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 3000; // 3s
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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

        new Thread(() -> doReplyOnMain(talker, text)).start();
    }

    /** 把 Hb / Ic 顺序放到主线程执行，并在 Ic 后 poke 主线程调度 */
    private static void doReplyOnMain(final String talker, final String text) {
        try {
            // 1) 克隆模板并打补丁
            final Object[] holder = new Object[1];
            boolean okProto = MainHook.runOnMainSync(() -> holder[0] = WechatG8Prototype.cloneAndPatch(talker, text));
            if (!okProto || holder[0] == null) {
                XposedBridge.log(MainHook.TAG + " [send:A] no template g8; skip");
                return;
            }
            final Object g8 = holder[0];

            // 2) Hb 拿 seq
            final long[] seqBox = new long[1];
            boolean okHb = MainHook.runOnMainSync(() ->
                    seqBox[0] = WechatKernelSender.callHbAndGetSeq(MainHook.sKernelMsgSender, g8, true, true)
            );
            long seq = okHb ? seqBox[0] : -1;
            XposedBridge.log(MainHook.TAG + " [send:A] Hb ret=" + seq);
            if (seq <= 0) return;

            // 3) 主线程小延迟后 Ic（模拟切前台调度tick）
            MAIN.postDelayed(() -> {
                try {
                    int ic = WechatKernelSender.callIc(MainHook.sKernelMsgSender, seq, g8, true);
                    XposedBridge.log(MainHook.TAG + " [send:A] Ic ret=" + ic);
                    if (ic <= 0) {
                        int alt = WechatKernelSender.tryU9OrRcOnce(MainHook.sKernelMsgSender, g8);
                        XposedBridge.log(MainHook.TAG + " [send:A] Ic<=0 fallback ret=" + alt);
                    }
                    pokeMain();
                    XposedBridge.log(MainHook.TAG + " [reply] -> " + talker + " ok=true path=A/Hb+Ic");
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + " [reply] Ic error: " + t);
                }
            }, 160);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [reply] exception: " + t);
        }
    }

    /** 尝试唤醒/刷新发送管线，等价于切回前台时的那个“心跳” */
    private static void pokeMain() {
        try {
            final Object sender = MainHook.sKernelMsgSender;
            if (sender != null) {
                String[] ms = new String[]{"notifyDataSetChanged", "doNotify", "flush", "G0", "p0"};
                for (String m : ms) {
                    try {
                        XposedHelpers.callMethod(sender, m);
                        XposedBridge.log(MainHook.TAG + " [poke] sender." + m + "()");
                        return;
                    } catch (Throwable ignored) {}
                }
            }
            MAIN.post(() -> {});
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [poke] error: " + t);
        }
    }
}

