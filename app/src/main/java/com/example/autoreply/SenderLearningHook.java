package com.example.autoreply;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 通过 hook i8.Hb(g8, boolean, boolean) 学习：
 *  - sender 实例（param.thisObject）
 *  - g8 实体类（param.args[0] 的实际类型）
 * 学到后再延迟安装 KernelSendTracer（避免 g8 未知导致的 findAndHookMethod 参数报错）
 */
public final class SenderLearningHook {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private SenderLearningHook() {}

    public static void install(final ClassLoader cl) {
        if (cl == null) return;
        if (!INSTALLED.compareAndSet(false, true)) return;

        try {
            final Class<?> i8 = cl.loadClass("com.tencent.mm.storage.i8");

            // 通过 Hb 学 sender/g8
            XposedHelpers.findAndHookMethod(i8, "Hb",
                    Object.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 学 sender 实例
                                if (MainHook.sKernelMsgSender == null && param.thisObject != null) {
                                    MainHook.sKernelMsgSender = param.thisObject;
                                    XposedBridge.log(MainHook.TAG + " [learn] sender instance learned: "
                                            + param.thisObject.getClass().getName());
                                }
                                // 学 g8 类
                                if (MainHook.sKernelMsgEntityCls == null && param.args != null && param.args.length > 0 && param.args[0] != null) {
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

            XposedBridge.log(MainHook.TAG + " SenderLearningHook install OK (via Hb).");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " SenderLearningHook install fail: " + t);
        }
    }
}

