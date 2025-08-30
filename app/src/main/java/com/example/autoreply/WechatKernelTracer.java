package com.example.autoreply;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WechatKernelTracer {

    public static void install(ClassLoader cl) {
        try {
            final Class<?> i8 = XposedHelpers.findClassIfExists("com.tencent.mm.storage.i8", cl);
            final Class<?> g8 = XposedHelpers.findClassIfExists("com.tencent.mm.storage.g8", cl);
            if (i8 == null || g8 == null) {
                XposedBridge.log(MainHook.TAG + " tracer miss i8/g8");
                return;
            }

            hook(i8, "Hb", new Class[]{g8, boolean.class, boolean.class});
            hook(i8, "tb", new Class[]{g8});
            hook(i8, "Ic", new Class[]{long.class, g8, boolean.class});
            hook(i8, "U9", new Class[]{g8});
            hook(i8, "Rc", new Class[]{g8});
            hook(i8, "rc", new Class[]{g8});
            hook(i8, "Ec", new Class[]{long.class, g8});

        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " tracer install error: " + t);
        }
    }

    private static void hook(Class<?> cls, String name, Class<?>[] params) {
        try {
            XposedHelpers.findAndHookMethod(cls, name, params, new XC_MethodHook() {

                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String retType = getReturnTypeName(param.method);
                    String methodName = getMemberName(param.method);
                    XposedBridge.log(MainHook.TAG + " [trace] BEFORE " + retType + " " + methodName + "(" + argsToString(param.args) + ")");

                    // 在 Hb/tb 的 BEFORE 保存模板（拿到一份“被内核认可”的 g8）
                    if ("Hb".equals(methodName) || "tb".equals(methodName)) {
                        if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                            WechatG8Prototype.saveTemplate(param.args[0]);
                        }
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    String retType = getReturnTypeName(param.method);
                    String methodName = getMemberName(param.method);
                    Object ret = param.getResult();
                    XposedBridge.log(MainHook.TAG + " [trace] AFTER  " + retType + " " + methodName + " ret=" + String.valueOf(ret));
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [trace] hook fail " + name + ": " + t);
        }
    }

    /** 兼容 Member / Method，拿到返回类型名 */
    private static String getReturnTypeName(Member m) {
        try {
            if (m instanceof Method) {
                return ((Method) m).getReturnType().getSimpleName();
            }
        } catch (Throwable ignored) {}
        return "void";
    }

    /** 兼容 Member / Method，拿到方法名 */
    private static String getMemberName(Member m) {
        try {
            return (m != null) ? m.getName() : "unknown";
        } catch (Throwable ignored) {}
        return "unknown";
    }

    /** 自带一份参数字符串化，避免依赖 argsToString() */
    private static String argsToString(Object[] args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (i > 0) sb.append(", ");
            if (a == null) {
                sb.append("null");
            } else {
                // 避免打印过长对象，尽量显示类型@hash
                String simple = a.getClass().getSimpleName();
                String id = Integer.toHexString(System.identityHashCode(a));
                sb.append(simple).append("@").append(id);
            }
        }
        return sb.toString();
    }
}

