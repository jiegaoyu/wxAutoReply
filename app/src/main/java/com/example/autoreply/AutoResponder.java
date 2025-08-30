package com.example.autoreply;

import android.text.TextUtils;

import de.robv.android.xposed.XposedBridge;

import java.util.concurrent.ConcurrentHashMap;

public class AutoResponder {

    // 3 秒内同一条自动回复去重
    private static final ConcurrentHashMap<String, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 3000L;

    // 发送中的重入保护：某些机型/Hook路径可能同步回调，避免递归触发
    private static final ThreadLocal<Boolean> SENDING = new ThreadLocal<>();
    private static boolean isSending() {
        return Boolean.TRUE.equals(SENDING.get());
    }

    /** 对外统一入口（异步） */
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

        new Thread(() -> reply(talker, text), "wxauto-reply").start();
    }

    /** 核心发送：模板克隆 + Hb(拿seq) + 轻延迟 + Ic + 踢队列(Rc/U9)，全部在主线程执行关键步骤 */
    private static void reply(String talker, String text) {
        if (isSending()) {
            XposedBridge.log(MainHook.TAG + " [reentry] already sending, skip");
            return;
        }
        SENDING.set(true);
        try {
            // 1) 模板克隆（后台线程做即可）
            Object g8 = WechatG8Prototype.cloneAndPatch(talker, text);
            if (g8 == null) {
                XposedBridge.log(MainHook.TAG + " [send:A] no template g8; skip");
                return;
            }

            // 2) Hb 放主线程执行，拿到 seq
            final long[] seqBox = new long[1];
            boolean okMain1 = MainHook.runOnMainSync(() -> {
                seqBox[0] = WechatKernelSender.callHbAndGetSeq(MainHook.sKernelMsgSender, g8, true, true);
            });
            long seq = seqBox[0];
            XposedBridge.log(MainHook.TAG + " [send:A] Hb(g8,true,true) -> " + seq + " (onMain=" + okMain1 + ")");
            if (seq <= 0) {
                XposedBridge.log(MainHook.TAG + " [reply] ok=false path=A/Hb (ret<=0)");
                return;
            }

            // 3) 轻微延迟，避免与模板时间戳完全重合
            try { Thread.sleep(150 + (long)(Math.random() * 150)); } catch (Throwable ignored) {}

            // 4) Ic 同样主线程执行
            final int[] icBox = new int[1];
            boolean okMain2 = MainHook.runOnMainSync(() -> {
                icBox[0] = WechatKernelSender.callIc(MainHook.sKernelMsgSender, seq, g8, true);
            });
            int icRet = icBox[0];
            XposedBridge.log(MainHook.TAG + " [send:A] Ic(seq,g8,true) -> " + icRet + " (onMain=" + okMain2 + ")");

            // 5) 踢队列：优先 Rc(g8)/或 U9(g8) 二选一
            MainHook.runOnMainSync(() -> {
                try {
                    int kick = WechatKernelSender.tryU9OrRcOnce(MainHook.sKernelMsgSender, g8);
                    XposedBridge.log(MainHook.TAG + " [send:A] kick queue via U9/Rc -> " + kick);
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + " [send:A] kick err " + t);
                }
            });

            XposedBridge.log(MainHook.TAG + " [reply] -> " + talker + " ok=true path=A/Hb+Ic(+kick)");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [reply] exception: " + t);
        } finally {
            SENDING.remove();
        }
    }
}

