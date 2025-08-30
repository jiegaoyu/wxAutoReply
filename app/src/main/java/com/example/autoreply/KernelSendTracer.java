package com.example.autoreply;

import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 仅在 sender/g8Class 就绪后安装；
 * 用 hookAllMethods 追踪 i8 的 Hb/tb/Ic/U9/Rc/Ec，
 * 并在 Hb/tb 的 AFTER 保存 g8 模板，避免精确参数签名不匹配的问题。
 */
public final class KernelSendTracer {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private KernelSendTracer() {}

    public static void install(final ClassLoader cl) {
        if (cl == null) return;
        if (INSTALLED.get()) return;

        if (!WechatKernelSender.isReady()) {
            XposedBridge.log(MainHook.TAG + " [trace] skip install, sender/entity not ready");
            return;
        }

        try {
            final Class<?> i8 = cl.loadClass("com.tencent.mm.storage.i8");

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
                                && param.args != null && param.args.length > 0 && param.args[0] != null) {
                            WechatG8Prototype.saveTemplate(param.args[0]);
                        }
                    } catch (Throwable ignored) {}
                }
            };

            // 统一改为 hookAllMethods，避免精确签名失败
            XposedBridge.hookAllMethods(i8, "Hb", traceHook);
            XposedBridge.hookAllMethods(i8, "tb", traceHook);
            XposedBridge.hookAllMethods(i8, "Ic", traceHook);
            XposedBridge.hookAllMethods(i8, "U9", traceHook);
            XposedBridge.hookAllMethods(i8, "Rc", traceHook);
            XposedBridge.hookAllMethods(i8, "Ec", traceHook);

            INSTALLED.set(true);
            XposedBridge.log(MainHook.TAG + " WechatKernelTracer installed.");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " KernelSendTracer install fail: " + t);
        }
    }
}

