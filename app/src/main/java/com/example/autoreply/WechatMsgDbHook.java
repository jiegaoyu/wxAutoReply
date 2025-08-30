package com.example.autoreply;

import android.content.ContentValues;
import android.content.Context;

import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * 监听微信 WCDB 对 message 表的插入：
 * - isSend=0 视为 “收到消息”，触发 AutoResponder.replyAsync() 静默回复
 * - isSend=1 视为 “发出消息”，做轻量采样日志（可用于对齐通道）
 *
 * 注意：尽量保持 beforeHook 开销极小，避免在热路径上造成卡顿。
 */
public final class WechatMsgDbHook {
private static final AtomicBoolean OBS_INSTALLED = new AtomicBoolean(false);
    private WechatMsgDbHook() {}

    // 采样（例如发出消息链路每 20 次打印一次堆栈）
    private static final AtomicInteger SAMPLE = new AtomicInteger();
    private static boolean shouldSample(int everyN) {
        int n = (everyN <= 0 ? 20 : everyN);
        return SAMPLE.incrementAndGet() % n == 0;
    }

    public static void install(final Context appCtx, final ClassLoader cl) {
    if (!OBS_INSTALLED.compareAndSet(false, true)) {
            XposedBridge.log(MainHook.TAG + " observer already installed");
            return;
        }
        try {
            // hook insert(String, String, ContentValues)
            XposedHelpers.findAndHookMethod(
                    "com.tencent.wcdb.database.SQLiteDatabase",
                    cl,
                    "insert",
                    String.class, String.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            onBeforeInsert(appCtx, cl, "insert", param.args);
                        }
                    });

            // hook insertWithOnConflict(String, String, ContentValues, int)
            XposedHelpers.findAndHookMethod(
                    "com.tencent.wcdb.database.SQLiteDatabase",
                    cl,
                    "insertWithOnConflict",
                    String.class, String.class, ContentValues.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            onBeforeInsert(appCtx, cl, "insertWithOnConflict", param.args);
                        }
                    });

            //（可选）insertOrThrow 有的版本会走到
            try {
                XposedHelpers.findAndHookMethod(
                        "com.tencent.wcdb.database.SQLiteDatabase",
                        cl,
                        "insertOrThrow",
                        String.class, String.class, ContentValues.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                onBeforeInsert(appCtx, cl, "insertOrThrow", param.args);
                            }
                        });
            } catch (Throwable ignored) {
                // 某些版本没有该重载，忽略即可
            }

            XposedBridge.log(MainHook.TAG + " WechatMsgDbHook installed.");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " WechatMsgDbHook install failed: " + t);
        }
    }

    /** 统一处理收到/发出消息的入口，尽量保持 O(1) 快速返回。 */
    private static void onBeforeInsert(Context appCtx, ClassLoader cl, String which, Object[] args) {
        // args: [0]=table(String) [1]=nullColumnHack(String) [2]=ContentValues [3]=(int conflict?) 可无
        final String table = String.valueOf(args[0]);
        if (!"message".equals(table)) return; // 仅关心 message 表

        final ContentValues cv = (args.length >= 3 && args[2] instanceof ContentValues)
                ? (ContentValues) args[2] : null;
        if (cv == null) return;

        // isSend: 0=收到、1=发出（历史版本一致）
        final int isSend = safeGetInt(cv, "isSend", -1);
        final String talker = cv.getAsString("talker");
        final String content = cv.getAsString("content");

        if (isSend == 0) {
            // 收到消息：触发静默回复
            XposedBridge.log(MainHook.TAG + " [MSG:IN] talker=" + talker +
                    " len=" + (content == null ? 0 : content.length()));
            try {
                AutoResponder.replyAsync(talker, content);
               

            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + " AutoResponder.replyAsync failed: " + t);
            }
            return;
        }

        if (isSend == 1) {
            // 发出消息：轻量日志 + 采样堆栈，便于验证发送通路是否来自我们
            XposedBridge.log(MainHook.TAG + " [MSG:OUT] talker=" + talker +
                    " len=" + (content == null ? 0 : content.length()));
                    
                    
                    
            if (shouldSample(20)) {
                dumpOutgoingStack(which);
            }
        }
        
        // 你已有：int isSend = cv.getAsInteger("isSend");
if (isSend == 1) {
    String talker1  = cv.getAsString("talker");
    String content1 = cv.getAsString("content");
    // ……你已有的日志……
    // 新增——把最近一次“手发”的 talker / content 缓存起来：
    MainHook.LAST_OUT_TALKER  = talker1;
    MainHook.LAST_OUT_CONTENT = content1;
}

        
        if ("message".equals(table) && isSend == 1 && talker != null && content != null) {
    // 这是实际写库（发出）动作 —— 用于“确认”上面的 send 成功
    SendConfirm.confirm(talker, content);
}

    }

    /** 仅用于排查：打印调用栈，标注发生在哪个 insert 重载。 */
    private static void dumpOutgoingStack(String which) {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder(512);
            sb.append(MainHook.TAG).append(" [TRACE] outgoing stack (")
              .append(which).append(", top 80):\n");
            int cnt = 0;
            for (StackTraceElement e : st) {
                // 跳过 getStackTrace 自身
                if (e.getClassName().startsWith("java.lang.Thread") &&
                        "getStackTrace".equals(e.getMethodName())) {
                    continue;
                }
                sb.append("    at ").append(e.toString()).append('\n');
                if (++cnt >= 80) break;
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " dumpOutgoingStack failed: " + t);
        }
    }

    /** 从 ContentValues 里快速安全取 int。 */
    private static int safeGetInt(ContentValues cv, String key, int def) {
        try {
            if (cv.containsKey(key)) {
                Integer v = cv.getAsInteger(key);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {}
        return def;
    }
}

