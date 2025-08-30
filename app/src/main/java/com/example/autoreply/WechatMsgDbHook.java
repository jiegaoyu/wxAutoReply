package com.example.autoreply;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class WechatMsgDbHook {

    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);

    public static void install(Context ctx, ClassLoader cl) {
        if (!sInstalled.compareAndSet(false, true)) return;

        try {
            // 这里留空给你已有的数据库 hook 逻辑
            XposedHelpers.findClass("com.tencent.mm.storage.i8", cl);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " WechatMsgDbHook install fail(inner): " + t);
        }
    }
}

