package com.tiecode.aarplugin.util;

import com.rapid.util.file.FileUtils;
import com.rapid.util.zip.ZipUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 纯打包逻辑：把宿主编译产物摆成 aar 目录再压缩。
 *
 * <p>不依赖工程的任何任务/上下文，方便单独验证。摆放规则完全跟着宿主产物走：
 *
 * <pre>
 * &lt;工程名&gt;.aar
 *  ├── AndroidManifest.xml   编译器生成的模块清单
 *  ├── classes.jar           本模块编译出来的 .class（不含 META-INF、不含 R 类）
 *  ├── R.txt                 资源链接产物
 *  ├── res/                  @安卓资源.图片 + 编译器 res + extra_res
 *  ├── assets/               @附加资源 的内容（与宿主打 APK 的落点一致）
 *  ├── jni/&lt;abi&gt;/*.so       @外部动态库 的内容（编译器输出目录名是 jniLibs）
 *  ├── libs/*.jar            编译器收集的依赖 jar
 *  └── proguard.txt          工程根目录 consumer-rules.pro（有才打）
 * </pre>
 */
public final class AarPacker {

    public static final String CLASSES_JAR = "classes.jar";
    public static final String R_TXT_NAME = "R.txt";

    /** classes.jar 里不打的 R 类（消费方按 R.txt 重新生成） */
    private static final Pattern R_CLASS_PATTERN = Pattern.compile("^(R|R\\$[A-Za-z0-9_]+)\\.class$");

    private AarPacker() {
    }

    /**
     * 组装并压缩 aar。
     *
     * @param stageDir      aar 目录的落地点（会被清空重建）
     * @param metadata      编译器产出的 metadata.json 模型
     * @param classesDir    宿主编译出来的 .class 目录
     * @param rTxtFile      资源链接产出的 R.txt（可为 null）
     * @param consumerRules 工程根目录的 consumer-rules.pro（可为 null）
     * @param outputAar     目标 aar 文件
     */
    public static File pack(File stageDir, AarMetadata metadata, File classesDir, File rTxtFile,
                            File consumerRules, File outputAar) throws IOException {
        FileUtils.deleteChildren(stageDir);
        if (!stageDir.exists() && !stageDir.mkdirs()) {
            throw new IOException("无法创建临时目录：" + stageDir);
        }

        // 1. classes.jar（只装本模块编译出来的 .class）
        buildClassesJar(classesDir, new File(stageDir, CLASSES_JAR));

        // 2. AndroidManifest.xml（编译器生成的模块清单）
        AarFiles.copyFileIfExists(AarFiles.toFile(metadata.productInfo.manifest),
                new File(stageDir, "AndroidManifest.xml"));

        // 3. res（编译器 res + extra_res + extraResDirs 合并到同一个 res/ 下）
        File stagedRes = new File(stageDir, "res");
        for (String resPath : metadata.collectResDirs()) {
            File resDir = AarFiles.toFile(resPath);
            if (resDir != null && resDir.isDirectory() && AarFiles.isNotEmptyDir(resDir)) {
                FileUtils.copyDir(resDir, stagedRes);
            }
        }

        // 4. R.txt（宿主资源链接任务产出）
        AarFiles.copyFileIfExists(rTxtFile, new File(stageDir, R_TXT_NAME));

        // 5. assets（@附加资源 的内容，与宿主打 APK 的落点一致）
        AarFiles.copyDirIfExists(AarFiles.toFile(metadata.productInfo.assetsDir),
                new File(stageDir, "assets"));

        // 6. jniLibs（@外部动态库 的内容）→ aar 标准目录 jni/
        AarFiles.copyDirIfExists(AarFiles.toFile(metadata.productInfo.jniLibsDir),
                new File(stageDir, "jni"));

        // 7. libs（编译器收集的依赖 jar）
        AarFiles.copyDirIfExists(AarFiles.toFile(metadata.productInfo.libsDir),
                new File(stageDir, "libs"));

        // 8. proguard.txt
        AarFiles.copyFileIfExists(consumerRules, new File(stageDir, "proguard.txt"));

        List<File> entries = new ArrayList<>();
        File[] children = stageDir.listFiles();
        if (children != null) {
            for (File child : children) {
                entries.add(child);
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("没有可打包的内容");
        }
        FileUtils.ensureParentDir(outputAar);
        ZipUtils.zipFiles(entries, outputAar.getAbsolutePath(), AAR_FILTER);
        return outputAar;
    }

    /**
     * 用普通 Zip 写 classes.jar：{@link java.util.jar.JarOutputStream} 会自动塞一个
     * {@code META-INF/MANIFEST.MF}，可 aar 里不该出现「META-INF 跑到 classes.jar 里」这种事。
     *
     * <p>只写本模块的 .class，不合并任何附加内容 —— 工程里没有的类，产物里也不该有。
     */
    public static void buildClassesJar(File classesDir, File output) throws IOException {
        ZipOutputStream zipStream = null;
        try {
            zipStream = new ZipOutputStream(new FileOutputStream(output));
            Set<String> added = new HashSet<>();
            if (classesDir != null && classesDir.isDirectory()) {
                for (File file : AarFiles.listAllFiles(classesDir)) {
                    String entryName = FileUtils.relativePathNoSep(file, classesDir);
                    if (entryName == null || entryName.isEmpty()) {
                        continue;
                    }
                    entryName = entryName.replace(File.separatorChar, '/');
                    if (entryName.startsWith("META-INF/") || R_CLASS_PATTERN.matcher(file.getName()).matches()) {
                        continue;
                    }
                    if (!added.add(entryName)) {
                        continue;
                    }
                    zipStream.putNextEntry(new ZipEntry(entryName));
                    zipStream.write(FileUtils.readFileBytes(file));
                    zipStream.closeEntry();
                }
            }
        } finally {
            if (zipStream != null) {
                zipStream.close();
            }
        }
    }

    /**
     * 把编译产物原样打成一个普通 jar（供「构建jar」用）。
     *
     * <p>不做 aar 那套 res/清单/jni 摆放：就是 {@code compileWithJavac} 中间目录里的
     * 类与资源原样压包（含 R 类；不会自己写 META-INF/MANIFEST.MF）。
     */
    public static void buildJar(File classesDir, File output) throws IOException {
        if (classesDir == null || !classesDir.isDirectory()) {
            throw new IOException("未找到编译输出目录：" + classesDir);
        }
        FileUtils.ensureParentDir(output);
        ZipOutputStream zipStream = null;
        try {
            zipStream = new ZipOutputStream(new FileOutputStream(output));
            Set<String> added = new HashSet<>();
            for (File file : AarFiles.listAllFiles(classesDir)) {
                String entryName = FileUtils.relativePathNoSep(file, classesDir);
                if (entryName == null || entryName.isEmpty()) {
                    continue;
                }
                entryName = entryName.replace(File.separatorChar, '/');
                if (!added.add(entryName)) {
                    continue;
                }
                zipStream.putNextEntry(new ZipEntry(entryName));
                zipStream.write(FileUtils.readFileBytes(file));
                zipStream.closeEntry();
            }
        } finally {
            if (zipStream != null) {
                zipStream.close();
            }
        }
    }

    /** 跳过不存在的文件和空目录（与宿主打 AAR 时一致）。 */
    private static final ZipUtils.ZipFileFilter AAR_FILTER = new ZipUtils.ZipFileFilter() {
        @Override
        public boolean filter(File file) {
            if (file == null || !file.exists()) {
                return true;
            }
            if (!file.isDirectory()) {
                return false;
            }
            File[] children = file.listFiles();
            return children == null || children.length == 0;
        }
    };
}
