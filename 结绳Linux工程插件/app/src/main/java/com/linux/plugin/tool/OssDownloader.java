package com.linux.plugin.tool;

import android.content.Context;

import com.alibaba.sdk.android.oss.ClientException;
import com.alibaba.sdk.android.oss.OSSClient;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSFederationToken;
import com.alibaba.sdk.android.oss.model.GetObjectRequest;
import com.alibaba.sdk.android.oss.model.GetObjectResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 通过阿里云 OSS SDK 下载公共读对象。 */
public final class OssDownloader {

    /** 对象所在公网 endpoint(广州地域)。 */
    private static final String ENDPOINT = "https://oss-cn-guangzhou.aliyuncs.com";
    /** 存储桶。 */
    private static final String BUCKET = "toolchain";
    private static final int COPY_BUFFER = 8192;

    private OssDownloader() {
    }

    /**
     * 下载 bucket 内的 objectKey 到本地文件。
     *
     * <p>对象为公共读,因此关闭请求签名({@code setIsAuthorizationRequired(false)}),
     * 用匿名凭证访问。下载先写入 {@code *.part},成功后重命名,避免中断留下残缺文件。
     *
     * @param context   用于构造 OSSClient 的上下文
     * @param objectKey OSS 对象键
     * @param target    本地目标文件
     */
    public static void download(Context context, String objectKey, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("创建下载目录失败: " + parent);
        }

        // 匿名凭证:getFederationToken 返回 null;配合关闭签名即可访问公共读对象。
        OSSCredentialProvider anonymous = new OSSCredentialProvider() {
            @Override
            public OSSFederationToken getFederationToken() {
                return null;
            }
        };

        OSSClient client = new OSSClient(context, ENDPOINT, anonymous);

        GetObjectRequest request = new GetObjectRequest(BUCKET, objectKey);
        // 公共读对象无需签名。
        request.setIsAuthorizationRequired(false);

        File part = new File(target.getAbsolutePath() + ".part");
        try {
            GetObjectResult result = client.getObject(request);
            InputStream in = result.getObjectContent();
            try (OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[COPY_BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        } catch (ClientException | ServiceException e) {
            // 本地/服务端错误统一转 IOException,交由上层任务处理。
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("OSS 下载失败: " + objectKey, e);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }

        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!part.renameTo(target)) {
            throw new IOException("重命名下载文件失败: " + part + " -> " + target);
        }
    }
}
