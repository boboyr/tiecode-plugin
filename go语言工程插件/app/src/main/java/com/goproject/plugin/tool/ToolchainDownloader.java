package com.goproject.plugin.tool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把一个 HTTP(S) 文件下载到本地。零第三方依赖，只用 JDK 自带的 {@link HttpURLConnection}。
 *
 * <p>目前唯一的用途是从工具链地址下载 Android GCC 压缩包（见 {@link ToolchainManager}）。
 *
 * <p>这里刻意<b>不</b>引阿里云 OSS SDK：那个 aar 有 250 个类，而它内部同样是
 * {@link HttpURLConnection}，对我们的场景没有任何额外价值。需要凭证/断点续传/
 * 分片上传时才值得引入。
 *
 * <p>先写 {@code *.part} 再改名，避免中断留下残缺文件被当成「已安装」。
 */
public final class ToolchainDownloader {

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int COPY_BUFFER = 64 * 1024;

    private ToolchainDownloader() {
    }

    /**
     * 下载 url 到 target。
     *
     * @param url    完整的下载地址
     * @param target 本地目标文件
     */
    public static void download(String url, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("创建下载目录失败: " + parent);
        }

        File part = new File(target.getAbsolutePath() + ".part");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "gosdl3-plugin/1.0");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP " + code + ": " + url);
            }

            long total = conn.getContentLengthLong();
            long written = 0;
            InputStream in = conn.getInputStream();
            try (OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[COPY_BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    written += n;
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }

            if (total > 0 && written != total) {
                // 长度对不上说明连接中途断了，别留下半个包
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("下载不完整: 期望 " + total + " 字节，实际 " + written);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!part.renameTo(target)) {
            throw new IOException("重命名下载文件失败: " + part + " -> " + target);
        }
    }
}
