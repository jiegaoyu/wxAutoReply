package com.example.autoreply;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class SenderLearningHook {

    public static volatile Object sSenderInstance = null;
    public static volatile Class<?> sG8Class = null;

    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sTracerInstalled = new AtomicBoolean(false);

    public static void install(final ClassLoader cl) {
        if (!sInstalled.compareAndSet(false, true)) return;

        try {
            final Class<?> i8Cls = XposedHelpers.findClass("com.tencent.mm.storage.i8", cl);

            XC_MethodHook learnHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object sender = param.thisObject;
                    if (sender != null && sSenderInstance == null) {
                        sSenderInstance = sender;
                        XposedBridge.log(MainHook.TAG + " [learn] sender instance learned: " + sender.getClass().getName());
                    }
                    if (param.args != null) {
                        for (Object a : param.args) {
                            if (a != null && a.getClass().getName().endsWith(".g8")) {
                                if (sG8Class == null) {
                                    sG8Class = a.getClass();
                                    XposedBridge.log(MainHook.TAG + " [learn] g8 class: " + sG8Class.getName());
                                }
                            }
                        }
                    }

                    if (sSenderInstance != null && sG8Class != null && sTracerInstalled.compareAndSet(false, true)) {
                        try {
                            WechatKernelTracer.install(cl);
                            XposedBridge.log(MainHook.TAG + " WechatKernelTracer installed.");
                        } catch (Throwable t) {
                            XposedBridge.log(MainHook.TAG + " WechatKernelTracer install fail: " + t);
                        }
                    }
                }
            };

            // hook Hb(g8, boolean, boolean)
            try {
                XposedHelpers.findAndHookMethod(i8Cls, "Hb",
                        Object.class, boolean.class, boolean.class, learnHook);
            } catch (Throwable t) {
                try {
                    XposedHelpers.findAndHookMethod("com.tencent.mm.storage.i8", cl,
                            "Hb", XposedHelpers.findClass("com.tencent.mm.storage.g8", cl),
                            boolean.class, boolean.class, learnHook);
                } catch (Throwable t2) {
                    XposedBridge.log(MainHook.TAG + " SenderLearningHook Hb hook fail: " + t2);
                }
            }

            // hook tb(g8)
            try {
                XposedHelpers.findAndHookMethod(i8Cls, "tb", Object.class, learnHook);
            } catch (Throwable t) {
                try {
                    XposedHelpers.findAndHookMethod("com.tencent.mm.storage.i8", cl,
                            "tb", XposedHelpers.findClass("com.tencent.mm.storage.g8", cl),
                            learnHook);
                } catch (Throwable t2) {
                    XposedBridge.log(MainHook.TAG + " SenderLearningHook tb hook fail: " + t2);
                }
            }

        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " SenderLearningHook install fail: " + t);
        }
    }
}

