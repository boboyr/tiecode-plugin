package org.libsdl.app;

import com.rapid.api.Platform;
import com.rapid.api.logging.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一日志:走宿主 {@link Platform#getLogger()},输出到结绳控制台;
 * 同时兜底写入 {@code <linux环境>/tmp/sdl_plugin.log},确保日志一定可见。
 * 不使用 android.util.Log。
 */
public final class TieLog {

    private static final Object LOCK = new Object();
    private static volatile File sLogFile = null;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TieLog() {
    }

    /** 由页面在拿到库目录后调用,开启文件兜底日志。 */
    public static void initFileLog(String dir) {
        try {
            File d = new File(dir);
            if (!d.exists()) {
                d.mkdirs();
            }
            sLogFile = new File(d, "sdl_plugin.log");
            i("TieLog", "file log -> " + sLogFile);
        } catch (Throwable ignored) {
        }
    }

    private static Logger logger() {
        try {
            return Platform.getLogger();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void i(String tag, String msg) {
        System.out.println("I [" + tag + "] " + msg);
        toFile("I", tag, msg);
        Logger l = logger();
        if (l != null) {
            try { l.info("[" + tag + "] " + msg); } catch (Throwable ignored) {}
        }
    }

    public static void w(String tag, String msg) {
        System.out.println("W [" + tag + "] " + msg);
        toFile("W", tag, msg);
        Logger l = logger();
        if (l != null) {
            try { l.warning("[" + tag + "] " + msg); } catch (Throwable ignored) {}
        }
    }

    public static void e(String tag, String msg) {
        System.out.println("E [" + tag + "] " + msg);
        toFile("E", tag, msg);
        Logger l = logger();
        if (l != null) {
            try { l.error("[" + tag + "] " + msg); } catch (Throwable ignored) {}
        }
    }

    public static void e(String tag, String msg, Throwable t) {
        String full = msg + " : " + t;
        System.out.println("E [" + tag + "] " + full);
        toFile("E", tag, full);
        if (t != null) {
            toFile("E", tag, stackOf(t));
        }
        Logger l = logger();
        if (l != null) {
            try {
                l.error("[" + tag + "] " + full);
                if (t != null) {
                    l.trace(t);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static String stackOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static void toFile(String level, String tag, String msg) {
        File f = sLogFile;
        if (f == null) {
            return;
        }
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, true);
                String line = FMT.format(new Date()) + " " + level + " [" + tag + "] " + msg + "\n";
                out.write(line.getBytes("UTF-8"));
            } catch (Throwable ignored) {
            } finally {
                try { if (out != null) out.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
