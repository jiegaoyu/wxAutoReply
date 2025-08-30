package com.example.autoreply;

import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 仅在 sender/g8Class 就绪后安装；
 * 追踪 i8 的 Hb/tb/Ic/U9/Rc/Ec 调用，并在 Hb/tb 处保存 g8 模板。
 */
public final class KernelSendTracer {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private KernelSendTracer() {}

    public static void install(final ClassLoader cl) {
        if (cl == null) return;
        if (INSTALLED.get()) return;

        // 必须等到 SenderLearningHook 学到这两个
        if (!WechatKernelSender.isReady()) {
            XposedBridge.log(MainHook.TAG + " [trace] skip install, sender/entity not ready");
            return;
        }

        try {
            final Class<?> i8 = cl.loadClass("com.tencent.mm.storage.i8");
            final Class<?> g8Cls = MainHook.sKernelMsgEntityCls;

            XC_MethodHook traceHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Member m = param.method;
                        XposedBridge.log(MainHook.TAG + " [trace] BEFORE "
                                + (m != null ? m.toString() : "<?>")
                                + " args=" + Arrays.toString(param.args));
                    } catch (Throwable ignored) {}
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Member m = param.method;
                        XposedBridge.log(MainHook.TAG + " [trace] AFTER  "
                                + (m != null ? m.toString() : "<?>")
                                + " ret=" + String.valueOf(param.getResult()));

                        // 在 Hb / tb 上保存 g8 模板
                        String name = (m != null ? m.getName() : "");
                        if (("Hb".equals(name) || "tb".equals(name))
                                && param.args != null && param.args.length > 0 && g8Cls.isInstance(param.args[0])) {
                            WechatG8Prototype.saveTemplate(param.args[0]);
                        }
                    } catch (Throwable ignored) {}
                }
            };

            // 逐个 hook（参数要用已学到的 g8Cls）
            try { XposedHelpers.findAndHookMethod(i8, "Hb", g8Cls, boolean.class, boolean.class, traceHook); }
            catch (Throwable e) { XposedBridge.log(MainHook.TAG + " [trace] hook fail Hb: " + e); }

            try { XposedHelpers.findAndHookMethod(i8, "tb", g8Cls, traceHook); }
            catch (Throwable e) { XposedBridge.log(MainHook.TAG + " [trace] hook fail tb: " + e); }

            try { XposedHelpers.findAndHookMethod(i8, "Ic", long.class, g8Cls, boolean.class, traceHook); }
            catch (Throwable e) { XposedBridge.log(MainHook.TAG + " [trace] hook fail Ic: " + e); }

            try { XposedHelpers.findAndHookMethod(i8, "U9", g8Cls, traceHook); }
            catch (Throwable e) { XposedBridge.log(MainHook.TAG + " [trace] hook fail U9: " + e); }

            try { XposedHelpers.findAndHookMethod(i8, "Rc", g8Cls, traceHook); }
            catch (Throwable e) { XposedBridge.log(MainHook.TAG + " [trace] hook fail Rc: " + e); }

            try { XposedHelpers.findAndHookMethod(i8, "Ec", long.class, g8Cls, traceHook); }
            catch (Throwable e) { XposedBridge.log(MainHook.TAG + " [trace] hook fail Ec: " + e); }

            INSTALLED.set(true);
            XposedBridge.log(MainHook.TAG + " WechatKernelTracer installed.");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " KernelSendTracer install fail: " + t);
        }
    }
}

