package com.example.autoreply;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;

public class SenderLearningHook {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    public static void install(ClassLoader cl) {
        if (cl == null) return;
        if (!INSTALLED.compareAndSet(false, true)) {
            XposedBridge.log(MainHook.TAG + " SenderLearningHook already installed.");
            return;
        }
        try {
            Class<?> i8 = cl.loadClass("com.tencent.mm.storage.i8");
            findAndHookConstructor(i8, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // 记录 ClassLoader
                        if (MainHook.APP_CL == null) MainHook.APP_CL = cl;

                        // 学 sender
                        if (WechatKernelSender.learnSenderIfNull(param.thisObject)) {
                            XposedBridge.log(MainHook.TAG + " [learn] sender instance learned: "
                                    + param.thisObject.getClass().getName());
                        }

                        // 学 g8 class
                        WechatKernelSender.learnEntityClassIfNull(cl);

                        // 就绪即装 tracer（发送链路）
                        if (WechatKernelSender.isReady()) {
                            KernelSendTracer.install(cl);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(MainHook.TAG + " SenderLearningHook error: " + t);
                    }
                }
            });

            XposedBridge.log(MainHook.TAG + " SenderLearningHook install OK.");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " SenderLearningHook install fail: " + t);
        }
    }
}

