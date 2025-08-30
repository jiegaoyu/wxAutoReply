package com.example.autoreply;

import java.lang.reflect.Method;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class KernelSendTracer {
    private KernelSendTracer() {}

    public static void install(ClassLoader cl) {
        try {
            if (MainHook.sKernelMsgSender == null || MainHook.sKernelMsgEntityCls == null) {
                XposedBridge.log(MainHook.TAG + " [trace] skip: sender/entity not learned yet");
                return;
            }
            final Object  sender = MainHook.sKernelMsgSender;
            final Class<?> i8Cls = sender.getClass();
            final Class<?> g8Cls = MainHook.sKernelMsgEntityCls;

            Method[] methods = i8Cls.getDeclaredMethods();
            int hooked = 0;
            for (Method m : methods) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 0) continue;
                if (ps[0] != g8Cls) continue; // 只追踪形如 (g8, ...) 的方法

                try {
                    m.setAccessible(true); // 关键：避免反射访问受限
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                XposedBridge.log(MainHook.TAG + " [trace] BEFORE "
                                        + sig(m) + " args=" + previewArgs(param.args, g8Cls));
                            } catch (Throwable ignored) {}
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object ret = param.getResult();
                                XposedBridge.log(MainHook.TAG + " [trace] AFTER  "
                                        + sig(m) + " ret=" + String.valueOf(ret));
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + " [trace] hook fail " + m + " : " + t);
                }
            }
            XposedBridge.log(MainHook.TAG + " [trace] installed on i8=" + i8Cls.getName()
                    + ", traced methods=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [trace] install failed: " + t);
        }
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getReturnType().getSimpleName()).append(' ')
          .append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ps[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }

    private static String previewArgs(Object[] args, Class<?> g8Cls) {
        try {
            if (args == null) return "null";
            Object[] shown = new Object[Math.min(args.length, 4)];
            for (int i = 0; i < shown.length; i++) {
                Object a = args[i];
                if (i == 0 && a != null && a.getClass() == g8Cls) {
                    shown[i] = "g8{...}";
                } else if (a instanceof CharSequence s) {
                    shown[i] = HookUtils.preview(s.toString());
                } else {
                    shown[i] = (a == null ? "null" : a.getClass().getSimpleName() + ":" + String.valueOf(a));
                }
            }
            return Arrays.toString(shown) + (args.length > shown.length ? " ..(+" +
                    (args.length - shown.length) + " more)" : "");
        } catch (Throwable t) {
            return "<err:" + t + ">";
        }
    }
}

