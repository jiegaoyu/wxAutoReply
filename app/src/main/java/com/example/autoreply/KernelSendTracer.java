package com.example.autoreply;

import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class KernelSendTracer {
    private static volatile boolean INSTALLED = false;

    public static void install(ClassLoader cl) {
        if (INSTALLED || cl == null) return;
        try {
            Class<?> i8 = cl.loadClass("com.tencent.mm.storage.i8");

            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Member m = param.method;
                        String retType = (m instanceof java.lang.reflect.Method)
                                ? ((java.lang.reflect.Method) m).getReturnType().getSimpleName()
                                : "void";
                        XposedBridge.log(MainHook.TAG + " [trace] BEFORE " + retType + " "
                                + m.getName() + "(...)");
                    } catch (Throwable ignored) {}
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Member m = param.method;
                        String retType = (m instanceof java.lang.reflect.Method)
                                ? ((java.lang.reflect.Method) m).getReturnType().getSimpleName()
                                : "void";
                        XposedBridge.log(MainHook.TAG + " [trace] AFTER  " + retType + " "
                                + m.getName() + " ret=" + String.valueOf(param.getResult()));

                        // 在 Hb / tb 之后尝试保存模板
                        if ("Hb".equals(m.getName()) || "tb".equals(m.getName())) {
                            if (param.args != null && param.args.length > 0) {
                                Object g8 = param.args[0];
                                if (g8 != null) {
                                    WechatG8Prototype.saveTemplate(g8);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };

            // 只钩名字与参数个数，内部日志用
            findAndHookMethod(i8, "Hb", MainHook.sKernelMsgEntityCls, boolean.class, boolean.class, hook);
            findAndHookMethod(i8, "tb", MainHook.sKernelMsgEntityCls, hook);
            findAndHookMethod(i8, "Ic", long.class, MainHook.sKernelMsgEntityCls, boolean.class, hook);
            findAndHookMethod(i8, "U9", MainHook.sKernelMsgEntityCls, hook);
            findAndHookMethod(i8, "Rc", MainHook.sKernelMsgEntityCls, hook);
            try { findAndHookMethod(i8, "Ec", long.class, MainHook.sKernelMsgEntityCls, hook); } catch (Throwable ignored) {}

            INSTALLED = true;
            XposedBridge.log(MainHook.TAG + " [trace] installed on i8=" + i8.getName());
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " KernelSendTracer install fail: " + t);
        }
    }
}

