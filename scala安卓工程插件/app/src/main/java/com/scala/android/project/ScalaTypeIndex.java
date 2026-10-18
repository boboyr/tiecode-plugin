package com.scala.android.project;

import com.scala.android.compiler.ScalaClasspathIndexApi;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * classpath 类型索引（简单类名 → 全限定名），补全的"自动导入"和快速修复的"添加 import"共用。
 *
 * <p>扫一个几十 MB 的 jar 要几秒，所以只在后台建一次；调用方永远拿现成的快照，
 * 没建好就先用空表（补全/修复都只是少几个候选，不会卡）。
 */
public final class ScalaTypeIndex {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "scala-type-index");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean BUILDING = new AtomicBoolean(false);
    private static volatile String builtKey = null;
    private static final Map<String, String> TYPES = new ConcurrentHashMap<>();

    private ScalaTypeIndex() {
    }

    /** 当前可用的索引快照（可能为空）。 */
    public static Map<String, String> types() {
        return TYPES;
    }

    /** 按简单类名查全限定名；没有返回 null。 */
    public static String find(String simpleName) {
        return simpleName == null ? null : TYPES.get(simpleName);
    }

    /** 后台（重新）构建索引；同一时间只跑一个。 */
    public static void prefetch(List<File> classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return;
        }
        final String key = key(classpath);
        if (key.equals(builtKey) || !BUILDING.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                Map<String, String> types = ScalaClasspathIndexApi.uniqueTypes(classpath);
                if (types != null) {
                    TYPES.clear();
                    TYPES.putAll(types);
                }
                builtKey = key;
            } catch (Throwable ignored) {
                // 建索引失败只是没有自动导入候选。
            } finally {
                BUILDING.set(false);
            }
        });
    }

    private static String key(List<File> classpath) {
        StringBuilder builder = new StringBuilder();
        for (File file : classpath) {
            if (file != null) {
                builder.append(file.getAbsolutePath()).append(File.pathSeparatorChar);
            }
        }
        return Integer.toHexString(builder.toString().hashCode());
    }

    /** 便于调试。 */
    static Map<String, String> snapshot() {
        return Collections.unmodifiableMap(TYPES);
    }
}
