package com.example.autoreply;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 通过 hookAllMethods(i8, "Hb") 学习：
 *  - sender 实例 (param.thisObject)
 *  - g8 实体类 (param.args[0].getClass())
 * 学到后再延迟安装 KernelSendTracer（避免精确签名找不到导致报错）
 */
public final class SenderLearningHook {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private SenderLearningHook() {}

    public static void install(final ClassLoader cl) {
        if (cl == null) return;
        if (!INSTALLED.compareAndSet(false, true)) return;

        try {
            final Class<?> i8 = cl.loadClass("com.tencent.mm.storage.i8");

            // 不再使用 findAndHookMethod(Object.class,...)；改为 hookAllMethods
            XposedBridge.hookAllMethods(i8, "Hb", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 学 sender
                        if (MainHook.sKernelMsgSender == null && param.thisObject != null) {
                            MainHook.sKernelMsgSender = param.thisObject;
                            XposedBridge.log(MainHook.TAG + " [learn] sender instance learned: "
                                    + param.thisObject.getClass().getName());
                        }
                        // 学 g8 类
                        if (MainHook.sKernelMsgEntityCls == null
                                && param.args != null && param.args.length > 0 && param.args[0] != null) {
                            MainHook.sKernelMsgEntityCls = param.args[0].getClass();
                            XposedBridge.log(MainHook.TAG + " [learn] g8 class: "
                                    + MainHook.sKernelMsgEntityCls.getName());
                        }

                        // 条件满足再装 tracer（只装一次）
                        if (WechatKernelSender.isReady()) {
                            KernelSendTracer.install(cl);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(MainHook.TAG + " SenderLearningHook error: " + t);
                    }
                }
            });

            XposedBridge.log(MainHook.TAG + " SenderLearningHook install OK (via hookAllMethods Hb).");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " SenderLearningHook install fail: " + t);
        }
    }
}

