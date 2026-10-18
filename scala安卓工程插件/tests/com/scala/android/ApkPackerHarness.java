package com.scala.android;

import com.scala.android.util.ApkPacker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * APK 打包器自检：拿 {@code res.ap_} 与 {@code classes.dex} 打一个 APK 出来，
 * 打印条目清单与压缩方式（不依赖设备）。
 *
 * <pre>
 * java -cp out com.scala.android.ApkPackerHarness res.ap_ classes.dex out.apk [so...]
 * </pre>
 */
public final class ApkPackerHarness {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: ApkPackerHarness <res.ap_> <classes.dex> <out.apk> [so...]");
            return;
        }
        File resApk = new File(args[0]);
        File dex = new File(args[1]);
        File output = new File(args[2]);

        List<File> dexFiles = new ArrayList<>();
        dexFiles.add(dex);

        java.util.Map<String, List<File>> nativeLibs = ApkPacker.emptyNativeLibs();
        if (args.length > 3) {
            List<File> libs = new ArrayList<>();
            for (int i = 3; i < args.length; i++) {
                libs.add(new File(args[i]));
            }
            nativeLibs.put("arm64-v8a", libs);
        }

        long size = ApkPacker.pack(output, resApk, ApkPacker.sortDexFiles(dexFiles),
                nativeLibs, new ArrayList<>(), System.out::println);

        System.out.println("== 产物 " + output.getAbsolutePath() + "（" + size + " bytes）");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(output)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            int stored = 0;
            int deflated = 0;
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                boolean isStored = entry.getMethod() == java.util.zip.ZipEntry.STORED;
                if (isStored) {
                    stored++;
                } else {
                    deflated++;
                }
                System.out.println("   " + (isStored ? "STORED  " : "DEFLATE ")
                        + entry.getName() + "  " + entry.getSize());
            }
            System.out.println("   STORED " + stored + " 个 · DEFLATE " + deflated + " 个");
        }
    }
}
