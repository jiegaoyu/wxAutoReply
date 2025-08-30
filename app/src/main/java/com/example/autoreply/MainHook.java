package com.example.autoreply;

import android.app.Application;
import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "[WxAutoReply]";
    private static final String PKG = "com.tencent.mm";

    // 进程级一次性开关
    private static final AtomicBoolean sDidInit = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " init in process: " + lpparam.processName);
        if (!PKG.equals(lpparam.processName)) {
            XposedBridge.log(TAG + " skip (non-main process): " + lpparam.processName);
            return;
        }

        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!sDidInit.compareAndSet(false, true)) return;

                Context ctx = (Context) param.args[0];
                ClassLoader cl = ctx.getClassLoader();

                XposedBridge.log(TAG + " Application.attach hit: app=" + param.thisObject
                        + " ctx=" + ctx + " pid=" + android.os.Process.myPid()
                        + " th=" + Thread.currentThread().getName());

                try {
                    WechatMsgDbHook.install(ctx, cl);
                    XposedBridge.log(TAG + " WechatMsgDbHook installed.");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " WechatMsgDbHook install fail: " + t);
                }

                try {
                    SenderLearningHook.install(cl);
                    XposedBridge.log(TAG + " SenderLearningHook installed.");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " SenderLearningHook install fail: " + t);
                }

                if (SenderLearningHook.sSenderInstance != null && SenderLearningHook.sG8Class != null) {
                    try {
                        WechatKernelTracer.install(cl);
                        XposedBridge.log(TAG + " WechatKernelTracer installed.");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " WechatKernelTracer install fail: " + t);
                    }
                }

                XposedBridge.log(TAG + " init done on MAIN process: " + lpparam.processName);
            }
        });
    }
}

