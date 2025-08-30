package com.example.autoreply;

import java.util.concurrent.ConcurrentHashMap;

public class SendConfirm {
    // key: talker + '\n' + prefix(content)
    private static final ConcurrentHashMap<String, Long> ticks = new ConcurrentHashMap<>();

    public static void markWant(String talker, String content) {
        String key = key(talker, content);
        ticks.put(key, System.currentTimeMillis());
    }

    public static void markSeen(String talker, String content) {
        String key = key(talker, content);
        // 只有在窗口期内，我们才认为是确认
        Long t = ticks.get(key);
        if (t != null && System.currentTimeMillis() - t < 5000) {
            // 保留记录，由 waitOutgoingInserted 来判断
        }
    }

    public static boolean waitOutgoingInserted(String talker, String content, long timeoutMs) {
        String key = key(talker, content);
        long start = System.currentTimeMillis();
        // 先登记一下“我打算看到它”
        ticks.put(key, start);
        // 简单轮询（避免引入复杂同步）
        while (System.currentTimeMillis() - start < timeoutMs) {
            Long t = ticks.get(key);
            if (t != null && t < 0) return true;   // -1 表示已经确认
            try { Thread.sleep(60); } catch (InterruptedException ignore) {}
        }
        return false;
    }

    // 由 DB hook 调用：一旦看到匹配的 outgoing，就把 ticks 置为负数
    public static void confirm(String talker, String content) {
        String key = key(talker, content);
        if (ticks.containsKey(key)) {
            ticks.put(key, -1L);
        }
    }

    private static String key(String talker, String content) {
        String c = (content == null) ? "" : content;
        if (c.length() > 32) c = c.substring(0, 32);
        return talker + "\n" + c;
    }
}

