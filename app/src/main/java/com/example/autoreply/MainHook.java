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
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "[WxAutoReply][DBG]";

    // 应用 ClassLoader
    public static volatile ClassLoader APP_CL;

    // 由 SenderLearningHook 学到的发送器实例（i8）与消息实体类（g8 Class）
    public static volatile Object   sKernelMsgSender;
    public static volatile Class<?> sKernelMsgEntityCls;

    // 供 WechatMsgDbHook 记录最近一条“我发出的消息”（模板克隆/去重可用）
    public static volatile String LAST_OUT_TALKER;
    public static volatile String LAST_OUT_CONTENT;

    // 主线程 Handler（懒加载）
    private static volatile Handler MAIN_HANDLER;

    private static Handler mainHandler() {
        if (MAIN_HANDLER == null) {
            synchronized (MainHook.class) {
                if (MAIN_HANDLER == null) {
                    MAIN_HANDLER = new Handler(Looper.getMainLooper());
                }
            }
        }
        return MAIN_HANDLER;
    }

    /** 在主线程同步执行 runnable，返回是否成功（带 1.5s 超时兜底） */
    public static boolean runOnMainSync(Runnable r) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // 当前就在主线程，直接跑
                r.run();
                return true;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = new boolean[]{true};
            mainHandler().post(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    ok[0] = false;
                    XposedBridge.log(TAG + " runOnMainSync error: " + t);
                } finally {
                    latch.countDown();
                }
            });
            // 等待最多 1.5 秒
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
                XposedBridge.log(TAG + " runOnMainSync timeout");
                return false;
            }
            return ok[0];
        } catch (Throwable t) {
            XposedBridge.log(TAG + " runOnMainSync outer error: " + t);
            return false;
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.tencent.mm".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " handleLoadPackage: " + lpparam.packageName +
                " process=" + lpparam.processName + " pid=" + android.os.Process.myPid());

        // Hook Application.attach 拿真正的 ClassLoader
        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Application app = (Application) param.thisObject;
                            Context appCtx  = (Context) param.args[0];
                            APP_CL = app.getClassLoader();

                            XposedBridge.log(TAG + " Application.attach hit: app=" + app +
                                    " ctx=" + appCtx + " pid=" + android.os.Process.myPid() +
                                    " th=" + Thread.currentThread().getName());
                            XposedBridge.log(TAG + " APP_CL=" + APP_CL);

                            // 1) 学习发送器/实体类
                            try {
                                SenderLearningHook.install(APP_CL);
                                XposedBridge.log(TAG + " SenderLearningHook installed.");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " SenderLearningHook install error: " + t);
                            }

                            // 2) 入出库 Hook（触发自动回复 & 记录最近发出的消息）
                            try {
                                WechatMsgDbHook.install(appCtx, APP_CL);
                                XposedBridge.log(TAG + " WechatMsgDbHook installed.");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " WechatMsgDbHook install error: " + t);
                            }

                            // 3) 内核 Trace（抓模板、打印 Hb/tb/Ic/U9/Rc/Ec）
                            try {
                                WechatKernelTracer.install(APP_CL);
                                XposedBridge.log(TAG + " WechatKernelTracer installed.");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " WechatKernelTracer install error: " + t);
                            }

                        } catch (Throwable e) {
                            XposedBridge.log(TAG + " attach afterHook error: " + e);
                        }
                    }
                }
        );
    }
}

