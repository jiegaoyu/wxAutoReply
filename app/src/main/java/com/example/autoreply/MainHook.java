package com.example.autoreply;

import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // ==== 日志 TAG（成员变量，而非方法） ====
    public static final String TAG = "WxAutoReply";

    // ==== 可被其他类使用的共享状态 ====
    // 学到的“发消息核心”实例 com.tencent.mm.storage.i8
    public static volatile Object sKernelMsgSender = null;
    // 学到的消息实体类 com.tencent.mm.storage.g8
    public static volatile Class<?> sKernelMsgEntityCls = null;

    // talker -> talkerId 学习映射
    private static final Map<String, Integer> sTalkerIdMap = new ConcurrentHashMap<>();

    // ==== 日志工具 ====
    public static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedBridge.log("[" + TAG + "] " + msg + " : " + Log.getStackTraceString(t));
    }

    // ==== 供 tracer/其他类设置学习到的对象 ====
    public static void setSender(Object sender) {
        sKernelMsgSender = sender;
        if (sender != null) {
            log("[learn] sender instance learned: " + sender.getClass().getName());
        }
    }

    public static void setG8Class(Class<?> g8) {
        sKernelMsgEntityCls = g8;
        if (g8 != null) {
            log("[learn] g8 class: " + g8.getName());
        }
    }

    public static void putTalkerId(String talker, int id) {
        if (talker == null) return;
        sTalkerIdMap.put(talker, id);
        log("[learn] talkerId learned: " + talker + " -> " + id);
    }

    public static Integer getTalkerId(String talker) {
        if (talker == null) return null;
        return sTalkerIdMap.get(talker);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!"com.tencent.mm".equals(lpparam.packageName)) {
                return;
            }
            log("init in process: " + lpparam.packageName);

            // 安装内核跟踪器（负责学到 i8/g8，并打印 Hb/Ic/tb）
            WechatKernelTracer.install(lpparam.classLoader);

            log("init done on MAIN process: " + lpparam.packageName);
        } catch (Throwable t) {
            e("init failed", t);
        }
    }
}