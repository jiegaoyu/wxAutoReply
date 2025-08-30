package com.example.autoreply;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class KernelProbe {
    private KernelProbe() {}

    public static void install(ClassLoader cl) {
        // 尝试常见的 kernel 类名（不同版本混淆不同）
        String[] kernels = {
                "com.tencent.mm.kernel.h",
                "com.tencent.mm.kernel.g",
                "com.tencent.mm.kernel.ba",
                "com.tencent.mm.kernel.bt",
                "com.tencent.mm.kernel.i",
                "com.tencent.mm.kernel.c"
        };
        for (String k : kernels) {
            Class<?> c = XposedHelpers.findClassIfExists(k, cl);
            if (c == null) continue;
            hookClassServiceMethods(c);
        }
    }

    private static void hookClassServiceMethods(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                // 只关注：1个参数且是 Class，且返回 Object
                if (m.getParameterTypes().length == 1 &&
                    m.getParameterTypes()[0] == Class.class &&
                    m.getReturnType() != void.class) {

                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object arg = param.args[0];
                            Object ret = param.getResult();
                            String aName = (arg instanceof Class) ? ((Class<?>) arg).getName() : String.valueOf(arg);
                            String rName = (ret != null) ? ret.getClass().getName() : "null";
                            XposedBridge.log(MainHook.TAG + " [PROBE] "
                                    + c.getName() + "#" + m.getName()
                                    + "(Class) -> " + aName + " => " + rName);
                        }
                    });
                }
            }
            XposedBridge.log(MainHook.TAG + " KernelProbe installed on " + c.getName());
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " KernelProbe failed on " + c.getName() + " : " + t);
        }
    }
}

