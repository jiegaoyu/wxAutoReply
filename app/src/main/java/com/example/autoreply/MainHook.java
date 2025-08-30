package com.example.autoreply;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class MainHook implements IXposedHookLoadPackage {
    public static final String TAG = "[WxAutoReply]";

    public static volatile ClassLoader APP_CL = null;
    // 供其它类使用的“已学习到的”核心对象
    public static volatile Object   sKernelMsgSender   = null;   // com.tencent.mm.storage.i8 的实例
    public static volatile Class<?> sKernelMsgEntityCls = null;  // com.tencent.mm.storage.g8 的 Class

    private static final AtomicBoolean INSTALLED_ON_MAIN = new AtomicBoolean(false);

    private static String currentProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentProcessName");
            cur.setAccessible(true);
            Object o = cur.invoke(null);
            return o == null ? null : String.valueOf(o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isWechatMainProcess(String packageName) {
        String pn = currentProcessName();
        if (pn == null) return true;
        return packageName.equals(pn);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.tencent.mm".equals(lpparam.packageName)) return;

        findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Context ctx = (Context) param.args[0];
                    if (ctx == null) return;

                    if (!isWechatMainProcess(lpparam.packageName)) {
                        XposedBridge.log(TAG + " skip (non-main process): " + currentProcessName());
                        return;
                    }
                    if (!INSTALLED_ON_MAIN.compareAndSet(false, true)) {
                        XposedBridge.log(TAG + " already installed. skip.");
                        return;
                    }

                    APP_CL = ctx.getClassLoader();
                    XposedBridge.log(TAG + " Application.attach hit: app=" + param.thisObject
                            + " ctx=" + ctx + " pid=" + android.os.Process.myPid()
                            + " th=" + Thread.currentThread().getName());

                    try {
                        WechatMsgDbHook.install(ctx, APP_CL); // 你仓库已有的方法签名
                        XposedBridge.log(TAG + " WechatMsgDbHook installed.");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " WechatMsgDbHook install error: " + t);
                    }

                    try {
                        SenderLearningHook.install(APP_CL);
                        XposedBridge.log(TAG + " SenderLearningHook installed.");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " SenderLearningHook install error: " + t);
                    }

                    try {
                        WechatKernelTracer.install(APP_CL);
                        XposedBridge.log(TAG + " WechatKernelTracer installed.");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " WechatKernelTracer install error: " + t);
                    }

                    XposedBridge.log(TAG + " init done on MAIN process: " + currentProcessName());
                } catch (Throwable e) {
                    XposedBridge.log(TAG + " attach afterHook error: " + e);
                }
            }
        });
    }

    // 供 AutoResponder 等主线程同步执行
    public static boolean runOnMainSync(Runnable r) {
        try {
            final Object lock = new Object();
            final boolean[] ok = {false};
            Handler h = new Handler(Looper.getMainLooper());
            synchronized (lock) {
                h.post(() -> {
                    try { r.run(); ok[0] = true; } catch (Throwable ignored) {}
                    synchronized (lock) { lock.notifyAll(); }
                });
                lock.wait(1500);
            }
            return ok[0];
        } catch (Throwable t) {
            XposedBridge.log(TAG + " runOnMainSync error: " + t);
            return false;
        }
    }
}

