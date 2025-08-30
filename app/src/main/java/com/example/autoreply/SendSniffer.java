package com.example.autoreply;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SendSniffer {
    private static final String SP = "wx_auto_reply_sniffer";
    private static final String K_CLASS = "cls";
    private static final String K_METHOD = "m";
    private static final String K_SIGNATURE = "sig"; // 参数类型签名，用逗号连接

    public static void install(ClassLoader cl, Context ctx) {
        try {
            Class<?> sqlite = XposedHelpers.findClass("android.database.sqlite.SQLiteDatabase", cl);

            XC_MethodHook cb = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        String table = (String) p.args[0];
                        if (!"message".equalsIgnoreCase(table)) return;

                        ContentValues cv = (ContentValues) p.args[1];
                        if (cv == null) return;

                        Integer isSend = cv.getAsInteger("isSend");
                        Integer type   = cv.getAsInteger("type");
                        if (isSend == null || type == null) return;
                        if (isSend != 1 || type != 1) return; // 自己发出的文本（你手动点的那次）

                        // 抓一份栈
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        for (StackTraceElement e : st) {
                            String cn = e.getClassName();
                            if (cn == null) continue;
                            if (!cn.startsWith("com.tencent.mm")) continue;

                            String mn = e.getMethodName();
                            // 记录“第一个”进入微信域的方法作为候选
                            // 真实发送方法不一定就是这一层，但足够作为定位入口；我们把签名作为“动态适配钩子”
                            SharedPreferences sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
                            sp.edit()
                              .putString(K_CLASS, cn)
                              .putString(K_METHOD, mn)
                              .putString(K_SIGNATURE, "(String,String)") // 先占位，后面 SenderBridge 会自适配
                              .apply();

                            XposedBridge.log(MainHook.TAG + " sniff send entry ~ " + cn + "#" + mn);
                            break;
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(MainHook.TAG + " SendSniffer err " + t);
                    }
                }
            };

            // 也挂在 insert/insertWithOnConflict 上（但只处理 isSend=1）
            XposedHelpers.findAndHookMethod(sqlite, "insert",
                    String.class, String.class, ContentValues.class, cb);

            XposedHelpers.findAndHookMethod(sqlite, "insertWithOnConflict",
                    String.class, String.class, ContentValues.class, int.class, cb);

            XposedBridge.log(MainHook.TAG + " SendSniffer armed (watch isSend=1 inserts)");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " SendSniffer install failed " + t);
        }
    }

    static class Sig {
        final String cls, m, sig;
        Sig(String c, String m, String s) { this.cls=c; this.m=m; this.sig=s; }
        boolean isValid(){ return cls!=null && m!=null; }
        @Override public String toString(){ return cls + "#" + m + sig; }
    }

    public static Sig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
        return new Sig(
                sp.getString(K_CLASS, null),
                sp.getString(K_METHOD, null),
                sp.getString(K_SIGNATURE, null)
        );
    }
}

