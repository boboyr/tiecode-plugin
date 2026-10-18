package com.dsh.libgdxeditor;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 极简自检日志：把插件的关键事件写一行到 /sdcard/Tiecode/libgdx游戏引擎编辑器.log。
 * 装完插件后可以直接看这个文件确认「宿主有没有加载本插件 / 菜单项有没有被执行」。
 */
final class PluginLog {

    private static final long MAX = 64 * 1024;

    private PluginLog() {
    }

    static File file() {
        return new File(Environment.getExternalStorageDirectory(), "Tiecode/libgdx游戏引擎编辑器.log");
    }

    static void write(String event) {
        try {
            File f = file();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (f.exists() && f.length() > MAX) f.delete();
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + event + "\n";
            FileOutputStream out = new FileOutputStream(f, true);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(line);
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
