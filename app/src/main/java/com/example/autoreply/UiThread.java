package com.example.autoreply;

import android.os.Handler;
import android.os.Looper;

/** 把任务丢到主线程执行的小工具 */
public final class UiThread {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiThread() {}

    public static void run(Runnable r) {
        if (r == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }
}

