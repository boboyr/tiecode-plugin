package com.linux.plugin.tool;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

/** tar.xz 解压工具。 */
public final class TarXzUtils {

    private static final int COPY_BUFFER = 8192;

    private TarXzUtils() {
    }

    /**
     * 把 tar.xz 解压到输出目录,保留条目权限,并还原符号链接。
     *
     * <p>解压用 tukaani 的 {@link XZInputStream} 而非 commons-compress 的
     * {@code XZCompressorInputStream}:宿主 IDE 自身也打包了 commons-compress,
     * 父优先类加载会把同名类解析到宿主那份,而宿主缺少 tukaani 依赖,会在
     * {@code SingleXZInputStream} 处抛 NoClassDefFoundError。tukaani 命名空间宿主不含,
     * 因此必定使用插件自带实现。
     *
     * @param tarXzPath  tar.xz 文件路径
     * @param outputPath 解压输出目录
     */
    public static void extractTarXz(String tarXzPath, String outputPath) throws IOException {
        File targetDirectory = new File(outputPath);
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IOException("创建输出目录失败: " + targetDirectory);
        }
        String rootCanonical = targetDirectory.getCanonicalPath();

        try (InputStream fis = new FileInputStream(tarXzPath);
             XZInputStream xzIn = new XZInputStream(fis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(xzIn)) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                File outputFile = new File(outputPath, entry.getName());

                // 防 zip-slip:解压结果必须落在输出目录内。
                String outCanonical = outputFile.getCanonicalPath();
                if (!outCanonical.equals(rootCanonical)
                        && !outCanonical.startsWith(rootCanonical + File.separator)) {
                    throw new IOException("非法的压缩条目路径: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    File parent = outputFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    Path linkPath = outputFile.toPath();
                    Files.deleteIfExists(linkPath);
                    Files.createSymbolicLink(linkPath, Paths.get(entry.getLinkName()));
                    // 符号链接的权限由目标文件决定,跳过权限设置。
                    continue;
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buf = new byte[COPY_BUFFER];
                        int n;
                        while ((n = tarIn.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                }

                applyMode(outputFile, entry.getMode());
            }
        }
    }

    /** 按 tar mode 还原 POSIX 权限;个别文件失败不影响整体解压。 */
    private static void applyMode(File file, int mode) {
        try {
            Files.setPosixFilePermissions(file.toPath(),
                    PosixFilePermissions.fromString(convertModeToPosixString(mode)));
        } catch (Throwable ignored) {
        }
    }

    /** 把 tar 的 mode 位转成 "rwxr-xr-x" 形式的 POSIX 权限字符串。 */
    public static String convertModeToPosixString(int mode) {
        int owner = (mode >> 6) & 0x7;
        int group = (mode >> 3) & 0x7;
        int others = mode & 0x7;

        char[] perms = {'-', '-', '-', '-', '-', '-', '-', '-', '-'};
        if ((owner & 0x4) != 0) perms[0] = 'r';
        if ((owner & 0x2) != 0) perms[1] = 'w';
        if ((owner & 0x1) != 0) perms[2] = 'x';
        if ((group & 0x4) != 0) perms[3] = 'r';
        if ((group & 0x2) != 0) perms[4] = 'w';
        if ((group & 0x1) != 0) perms[5] = 'x';
        if ((others & 0x4) != 0) perms[6] = 'r';
        if ((others & 0x2) != 0) perms[7] = 'w';
        if ((others & 0x1) != 0) perms[8] = 'x';

        return new String(perms);
    }
}
