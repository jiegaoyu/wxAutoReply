package com.example.autoreply;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

public final class TraceLogger {
    public static final String TAG = "[WxAutoReply][DBG]";
    private TraceLogger() {}

    /** 便捷：打印一行 */
    public static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }

    /** 打印进程/线程信息 */
    public static void logProcThread(String prefix) {
        String tname = Thread.currentThread().getName();
        int pid = android.os.Process.myPid();
        String pname = getProcessNameSafe();
        XposedBridge.log(TAG + " " + prefix + " pid=" + pid + " proc=" + pname + " th=" + tname);
    }

    /** 逐帧打印（每帧一行，避免截断） */
    public static void printStackPerLine(StackTraceElement[] st, int max) {
        if (st == null) {
            log("[TRACE] <null stack>");
            return;
        }
        int n = Math.min(max, st.length);
        log("[TRACE] BEGIN frames=" + n);
        for (int i = 0; i < n; i++) {
            XposedBridge.log(TAG + " [TRACE] #" + String.format(Locale.US, "%02d", i) + " " + st[i]);
        }
        log("[TRACE] END");
    }

    /** 落盘到内部 files 目录，无需权限 */
    public static File dumpStackInternal(Context ctx, String prefix, StackTraceElement[] st) {
        try {
            if (ctx == null) {
                log("[TRACE] dumpStackInternal: ctx=null");
                return null;
            }
            File dir = new File(ctx.getFilesDir(), "wx_auto_reply");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS", Locale.US).format(new Date());
            File out = new File(dir, prefix + "-" + ts + ".log");
            try (FileOutputStream fos = new FileOutputStream(out, false);
                 PrintWriter pw = new PrintWriter(fos)) {
                pw.println("=== " + prefix + " @ " + ts + " pid=" + android.os.Process.myPid() +
                        " proc=" + getProcessNameSafe() + " th=" + Thread.currentThread().getName() + " ===");
                if (st == null) {
                    pw.println("<null stack>");
                } else {
                    for (int i = 0; i < st.length; i++) {
                        pw.printf(Locale.US, "#%02d %s%n", i, st[i].toString());
                    }
                }
                pw.flush();
            }
            log("[TRACE] file=" + out.getAbsolutePath());
            return out;
        } catch (Throwable t) {
            log("[TRACE] dumpStackInternal failed: " + t);
            return null;
        }
    }

    private static String getProcessNameSafe() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentProcessName").invoke(null);
            return String.valueOf(cur);
        } catch (Throwable ignore) {
            return "unknown";
        }
    }
}

