package com.example.autoreply;

import android.content.ContentValues;

import com.example.autoreply.HookUtils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 观测微信 WCDB 的消息写入/SQL 调用。
 * 目标：打印 message 表的 insert / insertWithOnConflict / execSQL（涉及 message）；
 * 如版本走预编译语句，则兜底打印 SQLiteStatement 的 executeInsert/executeUpdateDelete。
 */
public class WechatDbDebugHook {

    public void install(ClassLoader cl) {

        // 1) com.tencent.wcdb.database.SQLiteDatabase
        try {
            Class<?> dbCls = XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", cl);

            // insert(String table, String nullColumnHack, ContentValues values)
            XposedHelpers.findAndHookMethod(dbCls, "insert",
                    String.class, String.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String table = (String) p.args[0];
                            if (!"message".equals(table)) return;

                            ContentValues v = (ContentValues) p.args[2];
                            String talker  = v != null ? String.valueOf(v.get("talker"))  : null;
                            String content = v != null ? String.valueOf(v.get("content")) : null;
                            Integer type   = (v != null && v.get("type")   instanceof Integer) ? (Integer) v.get("type")   : null;
                            Integer isSend = (v != null && v.get("isSend") instanceof Integer) ? (Integer) v.get("isSend") : null;

                            XposedBridge.log(MainHook.TAG + " [DB] insert message " +
                                    " talker=" + talker +
                                    " isSend=" + isSend +
                                    " type="   + type +
                                    " content=" + HookUtils.preview(content));
                        }
                    });

            // insertWithOnConflict(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm)
            XposedHelpers.findAndHookMethod(dbCls, "insertWithOnConflict",
                    String.class, String.class, ContentValues.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String table = (String) p.args[0];
                            if (!"message".equals(table)) return;

                            ContentValues v = (ContentValues) p.args[2];
                            String talker  = v != null ? String.valueOf(v.get("talker"))  : null;
                            String content = v != null ? String.valueOf(v.get("content")) : null;
                            Integer type   = (v != null && v.get("type")   instanceof Integer) ? (Integer) v.get("type")   : null;
                            Integer isSend = (v != null && v.get("isSend") instanceof Integer) ? (Integer) v.get("isSend") : null;

                            XposedBridge.log(MainHook.TAG + " [DB] insertWithOnConflict message " +
                                    " talker=" + talker +
                                    " isSend=" + isSend +
                                    " type="   + type +
                                    " content=" + HookUtils.preview(content));
                        }
                    });

            // execSQL(String sql, Object[] bindArgs) —— 打印涉及 message 的语句
            XposedHelpers.findAndHookMethod(dbCls, "execSQL",
                    String.class, Object[].class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String sql = (String) p.args[0];
                            if (sql == null) return;
                            String low = sql.toLowerCase(java.util.Locale.US);
                            if (low.contains(" message ")
                                    || low.contains(" message(")
                                    || low.contains(" into message")
                                    || low.contains(" update message ")
                                    || low.contains(" delete from message")) {
                                XposedBridge.log(MainHook.TAG + " [DB] execSQL >>> " + HookUtils.preview(sql));
                            }
                        }
                    });

            XposedBridge.log(MainHook.TAG + " [DBG] WCDB SQLiteDatabase hooks installed.");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [ERR] install WCDB(SQLiteDatabase) hooks: " + t);
        }

        // 2) com.tencent.wcdb.database.SQLiteStatement —— 兜底（有些版本走预编译）
        try {
            Class<?> stCls = XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteStatement", cl);

            XposedHelpers.findAndHookMethod(stCls, "executeInsert",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            // 这里拿不到直观的 table/values，只做频次与对象观察
                            XposedBridge.log(MainHook.TAG + " [DB] executeInsert (statement) obj=" + p.thisObject);
                        }
                    });

            XposedHelpers.findAndHookMethod(stCls, "executeUpdateDelete",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            XposedBridge.log(MainHook.TAG + " [DB] executeUpdateDelete (statement) obj=" + p.thisObject);
                        }
                    });

            XposedBridge.log(MainHook.TAG + " [DBG] WCDB SQLiteStatement hooks installed.");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " [ERR] install WCDB(SQLiteStatement) hooks: " + t);
        }
    }
}

