package com.example.autoreply;

import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatKernelSender {

    private static final String TAG = MainHook.TAG;

    /** 最近发送 key 的去重集合（talker|text） */
    private static final LruSet SENT_KEYS = new LruSet(256);

    /** 简单 LRU：只保留最近 N 条 key，用于发送去重等 */
    static final class LruSet extends LinkedHashMap<String, Boolean> {
        private final int max;

        LruSet(int max) {
            // accessOrder = true：按访问顺序维护
            super(Math.max(16, max), 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > max;
        }

        /** 只放一次；已存在则返回 false */
        synchronized boolean putOnce(String key) {
            if (containsKey(key)) return false;
            put(key, Boolean.TRUE);
            return true;
        }
    }

    /** 调用 Hb(g8, boolean, boolean)，拿到 seq（>0 合法） */
    public static long callHbAndGetSeq(Object sender, Object g8, boolean a, boolean b) {
        try {
            Object ret = XposedHelpers.callMethod(sender, "Hb", g8, a, b);
            long seq = asLong(ret);
            XposedBridge.log(TAG + " [send:A] call Hb(g8," + a + "," + b + ") -> raw=" + ret + " rc=" + seq);
            return seq;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [send:A] call Hb failed: " + t);
            return -1;
        }
    }

    /** 调用 Ic(long, g8, boolean)，>0 表示已出队 */
    public static int callIc(Object sender, long seq, Object g8, boolean flag) {
        try {
            Object ret = XposedHelpers.callMethod(sender, "Ic", seq, g8, flag);
            int rc = asInt(ret);
            XposedBridge.log(TAG + " [send:A] call Ic(long,g8," + flag + ") -> raw=" + ret + " rc=" + rc);
            return rc;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [send:A] call Ic failed: " + t);
            return -1;
        }
    }

    /** 兜底打一枪：优先 U9(g8)->long，其次 Rc(g8)->int */
    public static int tryU9OrRcOnce(Object sender, Object g8) {
        try {
            Object ret = XposedHelpers.callMethod(sender, "U9", g8);
            int rc = asInt(ret);
            XposedBridge.log(TAG + " [send:A] call U9(g8) -> raw=" + ret + " rc=" + rc);
            if (rc > 0) return rc;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [send:A] call U9 failed: " + t);
        }
        try {
            Object ret = XposedHelpers.callMethod(sender, "Rc", g8);
            int rc = asInt(ret);
            XposedBridge.log(TAG + " [send:A] call Rc(g8) -> raw=" + ret + " rc=" + rc);
            return rc;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [send:A] call Rc failed: " + t);
            return -1;
        }
    }

    /**
     * 封装的一次性发送：
     * - 模板克隆 g8
     * - 去重
     * - Hb -> seq
     * - 轻微延迟
     * - Ic(seq, g8, true)
     * - 失败兜底 U9/Rc
     */
    public static boolean replyOnce(Object sender, String talker, String text) {
        try {
            if (sender == null) {
                XposedBridge.log(TAG + " [replyOnce] sender is null");
                return false;
            }
            if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(text)) {
                XposedBridge.log(TAG + " [replyOnce] skip empty talker/text");
                return false;
            }

            // 发送去重（同一 talker|text 短时间只发一次）
            String key = talker + "|" + text;
            if (!SENT_KEYS.putOnce(key)) {
                XposedBridge.log(TAG + " [dedup] skip duplicate send " + key);
                return false;
            }

            // 1) 模板克隆
            Object g8 = WechatG8Prototype.cloneAndPatch(talker, text);
            if (g8 == null) {
                XposedBridge.log(TAG + " [replyOnce] no template g8; skip send");
                return false;
            }

            // 2) Hb 拿 seq
            long seq = callHbAndGetSeq(sender, g8, true, true);
            if (seq <= 0) {
                XposedBridge.log(TAG + " [replyOnce] Hb ret<=0, abort path=A/Hb");
                return false;
            }

            // 3) 轻微延迟，避免时间戳/去重冲突
            try { Thread.sleep(120 + (long) (Math.random() * 120)); } catch (Throwable ignored) {}

            // 4) Ic 出队
            int icRet = callIc(sender, seq, g8, true);
            if (icRet <= 0) {
                int alt = tryU9OrRcOnce(sender, g8);
                XposedBridge.log(TAG + " [replyOnce] Ic<=0, fallback ret=" + alt);
            }

            XposedBridge.log(TAG + " [reply] -> " + talker + " ok=true path=A/Hb+Ic");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [replyOnce] exception: " + t);
            return false;
        }
    }

    /** 工具：把返回统一转换为 long */
    private static long asLong(Object v) {
        if (v == null) return -1;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Throwable ignored) { return -1; }
    }

    /** 工具：把返回统一转换为 int */
    private static int asInt(Object v) {
        if (v == null) return -1;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Long) return ((Long) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Throwable ignored) { return -1; }
    }
}

