package com.example.autoreply;

import android.content.Context;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SenderBridge {
    private static volatile MethodCache cached = null;

    public static void install(ClassLoader cl, Context ctx) {
        // 这里暂时无需 hook，留空即可；send 时动态反射即可
    }

    public static boolean sendText(ClassLoader cl, Context ctx, String talker, String text) {
        try {
            if (text == null || text.isEmpty()) return false;

            // 命中缓存则直发
            MethodCache c = cached;
            if (c != null) {
                return c.invoke(talker, text);
            }

            // 读取嗅探到的入口
            SendSniffer.Sig sig = SendSniffer.load(ctx);
            if (sig == null || !sig.isValid()) {
                XposedBridge.log(MainHook.TAG + " no sniffed entry yet, skip silent send");
                return false;
            }

            Class<?> cls = XposedHelpers.findClass(sig.cls, cl);

            // 尝试几种常见签名（实例/静态 & 参数顺序）
            // 1) boolean send(String talker, String content)
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(sig.m, String.class, String.class);
                m.setAccessible(true);
                Object inst = tryGetSingleton(cls);
                Object ret = (inst != null) ? m.invoke(inst, talker, text) : m.invoke(null, talker, text);
                cached = new MethodCache(inst, m, MethodForm.TALKER_CONTENT);
                XposedBridge.log(MainHook.TAG + " silent send via " + sig);
                return interpret(ret);
            } catch (NoSuchMethodException ignore) {}

            // 2) boolean send(String content, String talker)
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(sig.m, String.class, String.class);
                m.setAccessible(true);
                Object inst = tryGetSingleton(cls);
                Object ret = (inst != null) ? m.invoke(inst, text, talker) : m.invoke(null, text, talker);
                cached = new MethodCache(inst, m, MethodForm.CONTENT_TALKER);
                XposedBridge.log(MainHook.TAG + " silent send via " + sig + " (swapped)");
                return interpret(ret);
            } catch (NoSuchMethodException ignore) {}

            // 3) 其他签名可以根据你日志再补（如带 scene/int/boolean 的）
            XposedBridge.log(MainHook.TAG + " no matching signature for " + sig);
            return false;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " silent send err " + t);
            return false;
        }
    }

    private static Object tryGetSingleton(Class<?> cls) {
        try {
            // 常见：有 getInstance()/dkP()/xxxMgr() 之类
            for (String name : new String[]{"getInstance","a","b","dkP","dkQ"}) {
                try {
                    java.lang.reflect.Method gm = cls.getDeclaredMethod(name);
                    gm.setAccessible(true);
                    Object inst = gm.invoke(null);
                    if (inst != null && cls.isInstance(inst)) return inst;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null; // 可能是静态工具类
    }

    private static boolean interpret(Object ret) {
        if (ret == null) return true;
        if (ret instanceof Boolean) return (Boolean) ret;
        return true;
    }

    private enum MethodForm { TALKER_CONTENT, CONTENT_TALKER }

    private static class MethodCache {
        final Object inst;
        final java.lang.reflect.Method m;
        final MethodForm form;
        MethodCache(Object i, java.lang.reflect.Method m, MethodForm f) { this.inst=i; this.m=m; this.form=f; }
        boolean invoke(String talker, String content) throws Exception {
            Object ret;
            if (form == MethodForm.TALKER_CONTENT) {
                ret = (inst != null) ? m.invoke(inst, talker, content) : m.invoke(null, talker, content);
            } else {
                ret = (inst != null) ? m.invoke(inst, content, talker) : m.invoke(null, content, talker);
            }
            return interpret(ret);
        }
    }
}

