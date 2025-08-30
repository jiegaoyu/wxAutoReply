package com.example.autoreply;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "[WxAutoReply]";
    public static volatile Object   sKernelMsgSender   = null;  // SenderLearningHook 学到
    public static volatile Class<?> sKernelMsgEntityCls = null; // SenderLearningHook 学到

    // 可选：用于“最近一次手动发出”的回显去重等
    public static volatile String LAST_OUT_TALKER   = null;
    public static volatile String LAST_OUT_CONTENT  = null;
 public static volatile ClassLoader APP_CL = null;
    // 主线程同步执行一个任务
    public static boolean runOnMainSync(Runnable r) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run(); return true;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = new boolean[]{true};
            new Handler(Looper.getMainLooper()).post(() -> {
                try { r.run(); }
                catch (Throwable t) { ok[0] = false; XposedBridge.log(TAG+" runOnMainSync inner: "+t); }
                finally { latch.countDown(); }
            });
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
                XposedBridge.log(TAG+" runOnMainSync timeout");
                return false;
            }
            return ok[0];
        } catch (Throwable t) {
            XposedBridge.log(TAG+" runOnMainSync outer: "+t);
            return false;
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.tencent.mm".equals(lpparam.packageName)) return;

        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Context ctx = (Context) param.args[0];
                    ClassLoader appCl = ctx.getClassLoader();

                    XposedBridge.log(TAG + " Application.attach hit: app=" + param.thisObject
                            + " ctx=" + ctx + " pid=" + android.os.Process.myPid()
                            + " th=" + Thread.currentThread().getName());

                    // 1) 安装消息入库/出库触发点（你项目里已有，签名为 install(Context, ClassLoader)）
                    try {
                        WechatMsgDbHook.install(ctx, appCl);
                        XposedBridge.log(TAG + " WechatMsgDbHook installed.");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " WechatMsgDbHook install error: " + t);
                    }

                    // 2) 安装 sender 学习（学习 i8/g8）
                    try {
                        SenderLearningHook.install(appCl);
                        XposedBridge.log(TAG + " SenderLearningHook installed.");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " SenderLearningHook install error: " + t);
                    }

                    // 3) 安装内核 trace（Hb/tb/Ic/U9/Rc/Ec + 保存模板）
                    try {
                        WechatKernelTracer.install(appCl);
                        XposedBridge.log(TAG + " [trace] installed on i8=com.tencent.mm.storage.i8, traced methods=8");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " WechatKernelTracer install error: " + t);
                    }

                } catch (Throwable e) {
                    XposedBridge.log(TAG + " attach afterHook error: " + e);
                }
            }
        });
    }
}

