package com.example.autoreply;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;

public final class UiThread {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiThread() {}

    /** 在主线程同步执行 runnable，返回是否成功执行（捕获异常不抛出） */
    public static boolean runSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { r.run(); return true; } catch (Throwable t) { return false; }
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = { true };
        MAIN.post(() -> {
            try { r.run(); }
            catch (Throwable t) { ok[0] = false; }
            finally { latch.countDown(); }
        });
        try { latch.await(); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    /** 主线程异步投递 */
    public static void post(Runnable r) {
        MAIN.post(r);
    }
}

