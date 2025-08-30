package com.example.autoreply;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 统一存放微信核心对象的全局状态，尽量避免和 MainHook 强耦合 */
public final class KernelState {
    /** com.tencent.mm.storage.i8 实例（消息库/发送入口） */
    public static volatile Object sender = null;

    /** com.tencent.mm.storage.g8 Class（消息实体类） */
    public static volatile Class<?> g8Cls = null;

    /** talker -> talkerId 映射（可选） */
    private static final ConcurrentMap<String, Integer> TALKER_ID = new ConcurrentHashMap<>();

    private KernelState() {}

    public static void putTalkerId(String talker, int id) {
        if (talker == null || talker.isEmpty() || id <= 0) return;
        TALKER_ID.put(talker, id);
    }

    public static Integer getTalkerId(String talker) {
        if (talker == null || talker.isEmpty()) return null;
        return TALKER_ID.get(talker);
    }
}

