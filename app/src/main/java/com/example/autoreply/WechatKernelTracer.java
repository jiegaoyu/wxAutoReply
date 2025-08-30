package com.example.autoreply;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class WechatKernelTracer {

    private static final String TAG = MainHook.TAG + " [trace]";

    private WechatKernelTracer() {}

    /** 由 MainHook 调用：打开 i8 的 Hb/tb/Ic/U9/Rc/Ec trace，并在 Hb/tb 上保存 g8 模板 */
    public static void install(ClassLoader cl) {
        try {
            final Class<?> i8Cls = XposedHelpers.findClassIfExists("com.tencent.mm.storage.i8", cl);
            if (i8Cls == null) {
                XposedBridge.log(TAG + " i8 class not found");
                return;
            }
            XposedBridge.log(TAG + " installed on i8=" + i8Cls.getName() + ", traced methods=6");

            // 统一 hook 6 个方法名（参数多态，用 hookAllMethods 更稳）
            hookAll(i8Cls, "Hb");   // long Hb(g8, boolean, boolean)
            hookAll(i8Cls, "tb");   // long tb(g8)
            hookAll(i8Cls, "Ic");   // int  Ic(long, g8, boolean)
            hookAll(i8Cls, "U9");   // long U9(g8)
            hookAll(i8Cls, "Rc");   // int  Rc(g8)
            hookAll(i8Cls, "Ec");   // long Ec(long, g8)

        } catch (Throwable t) {
            XposedBridge.log(TAG + " install error: " + t);
        }
    }

    private static void hookAll(Class<?> cls, String name) {
        XposedBridge.hookAllMethods(cls, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Method m = (Method) param.method;
                    String ret = m.getReturnType() != null ? m.getReturnType().getSimpleName() : "void";
                    String argsPretty = prettyArgs(param.args);
                    XposedBridge.log(TAG + " BEFORE " + ret + " " + name + "(" + sigOf(m) + ") args=" + argsPretty);

                    // 在 Hb/tb 前后我们都可以拿到 g8。为了稳妥，before/after 都尝试保存一次模板。
                    if (("Hb".equals(name) || "tb".equals(name)) && param.args != null && param.args.length > 0) {
                        Object g8 = param.args[0];
                        if (g8 != null) {
                            WechatG8Prototype.saveTemplate(g8);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Method m = (Method) param.method;
                    String ret = m.getReturnType() != null ? m.getReturnType().getSimpleName() : "void";
                    String out = (param.getResult() == null) ? "null" : String.valueOf(param.getResult());
                    XposedBridge.log(TAG + " AFTER  " + ret + " " + name + "(" + sigOf(m) + ") ret=" + out);

                    // 再尝试一次保存模板（有时 g8 在 after 会更“完整”）
                    if (("Hb".equals(name) || "tb".equals(name)) && param.args != null && param.args.length > 0) {
                        Object g8 = param.args[0];
                        if (g8 != null) {
                            WechatG8Prototype.saveTemplate(g8);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    // --- 小工具 ---

    private static String prettyArgs(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (i > 0) sb.append(", ");
            if (a == null) { sb.append("null"); continue; }
            String cls = a.getClass().getSimpleName();
            // 打印 g8 时只露个类型和 hash，避免刷屏
            if (cls.equals("g8") || a.getClass().getName().endsWith(".storage.g8")) {
                sb.append("g8@").append(Integer.toHexString(System.identityHashCode(a)));
            } else {
                sb.append(cls);
                if (a instanceof Boolean || a instanceof Number || a instanceof CharSequence) {
                    sb.append(":").append(String.valueOf(a));
                }
            }
        }
        return sb.append("]").toString();
    }

    private static String sigOf(Method m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            Class<?> p = ps[i];
            sb.append(p == null ? "null" : p.getSimpleName());
        }
        return sb.toString();
    }
}

