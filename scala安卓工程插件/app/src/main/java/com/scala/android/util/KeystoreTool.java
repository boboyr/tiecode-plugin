package com.scala.android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Enumeration;

/**
 * 密钥库处理（纯 Java，可在普通 JVM 上单测）。
 *
 * <h3>为什么要转成 JKS</h3>
 * 宿主的签名工具是它内置的 apksigner（{@code AndroidApkSigner} → {@code ApkSignerTool.main}，
 * 命令只有 {@code --ks/--ks-key-alias/--ks-pass/--key-pass}，<b>没有 --ks-type</b>）。
 * 这种调用方式只认 JKS：
 * <ul>
 *   <li>宿主自己的 {@code SignAPKTask} 里就写着
 *       <pre>if (storeParams.type == KeyType.PKCS12) throw new RuntimeException("V2签名不能使用PKCS12签名证书，请更换JKS或PK8");</pre></li>
 *   <li>喂 PKCS12 进去时 apksig 加载失败 → {@code ApkSignerTool.main} 直接
 *       {@code System.exit(...)}，表现出来不是构建报错，而是<b>结绳整个闪退</b>。</li>
 * </ul>
 * 所以这里做两件事：
 * <ol>
 *   <li><b>预校验</b>：真的把密钥库打开、把私钥取出来。不合法就提前抛出可读错误，
 *       绝不把坏参数丢给那个会 {@code System.exit} 的工具；</li>
 *   <li><b>归一化</b>：非 JKS（PKCS12 等）自动转成插件缓存目录下的 JKS 副本，
 *       别名/口令原样保留，用户不需要改工程里的密钥库文件。</li>
 * </ol>
 */
public final class KeystoreTool {

    public interface Log {
        void line(String message);
    }

    /** 签名参数（与宿主 {@code KeyStoreParams} 一一对应，但不依赖 Android）。 */
    public static final class Spec {
        /** {@code JKS} / {@code PKCS12} / {@code X509}。 */
        public String type;
        public String storePath;
        public String alias;
        public String storePassword;
        public String keyPassword;
        /** 归一化时用到的目录（可写）。 */
        public File cacheDir;

        public Spec copy() {
            Spec spec = new Spec();
            spec.type = type;
            spec.storePath = storePath;
            spec.alias = alias;
            spec.storePassword = storePassword;
            spec.keyPassword = keyPassword;
            spec.cacheDir = cacheDir;
            return spec;
        }

        @Override
        public String toString() {
            return type + " " + storePath + " alias=" + alias;
        }
    }

    private KeystoreTool() {
    }

    /** 读文件头判类型：JKS 以 {@code FE ED FE ED} 开头，PKCS12 是 DER（{@code 30 82}）。 */
    public static String sniffType(File keystore) {
        if (keystore == null || !keystore.isFile()) {
            return null;
        }
        try (InputStream input = new FileInputStream(keystore)) {
            byte[] head = new byte[8];
            int read = input.read(head);
            if (read >= 4 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xED
                    && (head[2] & 0xFF) == 0xFE && (head[3] & 0xFF) == 0xED) {
                return "JKS";
            }
            if (read >= 1 && (head[0] & 0xFF) == 0x30) {
                return "PKCS12";
            }
        } catch (Throwable ignored) {
            // 落到下面的按扩展名判断
        }
        return null;
    }

    // ---------------------------------------------------- 运行时类型可用性探测

    /** 探测过的可用类型（null = 还没探测）。 */
    private static volatile java.util.List<String> availableTypes;

    /** 逐一探测的候选类型（Android 上常见情况：没有 JKS，只有 BKS/PKCS12）。 */
    private static final String[] CANDIDATE_TYPES = {
            "JKS", "PKCS12", "BKS", "BKS-V1", "UBER"
    };

    /**
     * 本运行时可用的密钥库类型。
     *
     * <p>Android 上 {@code KeyStore.getInstance("JKS")} 抛
     * {@code KeyStoreException: JKS not found} 是很常见的（JKS 由 SUN provider 提供，
     * 不少 ROM/运行时并不注册它，只有 BouncyCastle 的 BKS/PKCS12）。
     * 宿主的测试证书之所以走 {@code --key/--cert}（X509），就是为了绕开这一点。
     */
    public static java.util.List<String> availableTypes() {
        java.util.List<String> cached = availableTypes;
        if (cached != null) {
            return cached;
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String type : CANDIDATE_TYPES) {
            if (isTypeAvailable(type)) {
                result.add(type);
            }
        }
        availableTypes = result;
        return result;
    }

    /** 单个类型是否可用（能 getInstance 且能 load(null,null)）。 */
    public static boolean isTypeAvailable(String type) {
        if (type == null || type.isEmpty()) {
            return false;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(null, null);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 供日志展示：{@code 可用 [PKCS12, BKS]，不可用 [JKS]}。 */
    public static String describeAvailableTypes() {
        java.util.List<String> available = availableTypes();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String type : CANDIDATE_TYPES) {
            if (!available.contains(type)) {
                missing.add(type);
            }
        }
        return "可用 " + available + " · 不可用 " + missing;
    }

    /** 文件 SHA-256（十六进制小写）；读不了返回 null。 */
    public static String sha256(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream input = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest()) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Throwable error) {
            return null;
        }
    }

    /** 有效类型：文件头优先，其次扩展名/报告类型。 */
    public static String effectiveType(Spec spec) {
        String sniffed = spec == null ? null : sniffType(new File(spec.storePath == null ? "" : spec.storePath));
        if (sniffed != null) {
            return sniffed;
        }
        if (spec != null && spec.type != null && !spec.type.isEmpty()) {
            return spec.type;
        }
        return spec == null || spec.storePath == null ? "JKS"
                : typeByExtension(new File(spec.storePath));
    }

    /** 公开的校验入口：按给定类型打开、取私钥、取证书链（不做任何转换）。 */
    public static void validateWith(Spec spec, String type, Log log) throws IOException {
        validate(spec, new File(spec.storePath), type, log);
    }

    /** 按扩展名兜底判断类型。 */
    public static String typeByExtension(File keystore) {
        String name = keystore == null ? "" : keystore.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".pkcs12")) {
            return "PKCS12";
        }
        if (name.endsWith(".pk8") || name.endsWith(".der") || name.endsWith(".pem")
                || name.endsWith(".x509")) {
            return "X509";
        }
        return "JKS";
    }

    /**
     * 归一化：校验 + （必要时）转成 JKS。
     *
     * @return 可以直接喂给宿主 apksigner 的参数
     * @throws IOException 参数不可用时抛出，附可读原因
     */
    public static Spec normalize(Spec spec, Log log) throws IOException {
        return normalize(spec, log, defaultKeystoreType());
    }

    /**
     * 宿主的 apksigner 加载密钥库时用的是 {@code KeyStore.getDefaultType()}
     * （它只传 {@code --ks}，从没传 {@code --ks-type}；apksig 的 {@code SignerParams}
     * 里就引用 {@code KeyStore.getDefaultType}）。所以这里也转成同一个类型 ——
     * 我们和 apksigner 在同一个进程、同一个 Android runtime 里，
     * 拿到的默认类型必然一致，加载就一定对得上。
     */
    public static String defaultKeystoreType() {
        try {
            String type = KeyStore.getDefaultType();
            if (type != null && !type.isEmpty()) {
                return type.toUpperCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) {
            // 落到 JKS 兜底
        }
        return "JKS";
    }

    /** 供单测指定目标类型（{@code targetType} 为空时用平台默认类型）。 */
    public static Spec normalize(Spec spec, Log log, String targetType) throws IOException {
        String target = targetType == null || targetType.isEmpty() ? "JKS" : targetType;
        if (spec == null || spec.storePath == null || spec.storePath.isEmpty()) {
            throw new IOException("没有可用的签名密钥库");
        }
        File source = new File(spec.storePath);
        if (!source.isFile()) {
            throw new IOException("签名密钥库不存在：" + source);
        }

        String sniffed = sniffType(source);
        String type = sniffed != null ? sniffed : (spec.type == null ? "JKS" : spec.type);
        if ("X509".equalsIgnoreCase(type) || "X509".equalsIgnoreCase(spec.type)) {
            // pk8 + 证书：宿主 apksigner 用 --key/--cert，无需转换
            spec.type = "X509";
            info(log, "签名证书：X509（私钥 + 证书）");
            return spec;
        }
        if (!type.equalsIgnoreCase(target)) {
            return convert(spec, source, type, target, log);
        }

        spec.type = target;
        validate(spec, source, target, log);
        return spec;
    }

    /** 打开密钥库并取出私钥，确认别名与口令都对得上。 */
    private static void validate(Spec spec, File storeFile, String type, Log log) throws IOException {
        try {
            KeyStore keyStore = KeyStore.getInstance(type);
            try (InputStream input = new FileInputStream(storeFile)) {
                keyStore.load(input, password(spec.storePassword));
            }
            String alias = keyStore.getCertificateAlias(null);
            String resolved = resolveAlias(keyStore, spec.alias);
            Key key = keyStore.getKey(resolved, password(spec.keyPassword));
            Certificate[] chain = keyStore.getCertificateChain(resolved);
            if (key == null) {
                throw new IOException("别名 " + resolved + " 不是私钥条目");
            }
            if (chain == null || chain.length == 0) {
                throw new IOException("别名 " + resolved + " 没有证书链");
            }
            spec.alias = resolved;
            info(log, "签名密钥库校验通过：" + type + " · 别名 " + resolved
                    + " · 证书 " + chain[0].getType());
        } catch (IOException error) {
            throw new IOException("校验签名密钥库失败（" + type + " " + storeFile
                    + "，别名 " + spec.alias + "）：" + error.getMessage()
                    + "。请检查 build.sbt 里的 androidReleaseKeystore*/…Password 配置", error);
        } catch (Throwable error) {
            throw new IOException("校验签名密钥库失败（" + type + " " + storeFile + "）：" + error, error);
        }
    }

    /** PKCS12 等 → 插件缓存目录下"平台默认类型"的副本（别名、口令原样保留）。 */
    private static Spec convert(Spec spec, File source, String sourceType, String targetType,
                                Log log) throws IOException {
        File cacheDir = spec.cacheDir;
        if (cacheDir == null) {
            cacheDir = new File(System.getProperty("java.io.tmpdir"), "scala-android-keystore");
        }
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            throw new IOException("无法创建密钥库缓存目录 " + cacheDir);
        }
        String baseName = source.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(cacheDir, baseName + "." + targetType.toLowerCase(java.util.Locale.ROOT));
        try {
            KeyStore sourceStore = KeyStore.getInstance(sourceType);
            try (InputStream input = new FileInputStream(source)) {
                sourceStore.load(input, password(spec.storePassword));
            }
            String resolved = resolveAlias(sourceStore, spec.alias);
            Key key = sourceStore.getKey(resolved, password(spec.keyPassword));
            Certificate[] chain = sourceStore.getCertificateChain(resolved);
            if (key == null || chain == null || chain.length == 0) {
                throw new IOException("别名 " + resolved + " 不是可用的私钥条目");
            }

            KeyStore convertedStore = KeyStore.getInstance(targetType);
            convertedStore.load(null, null);
            convertedStore.setKeyEntry(resolved, key, password(spec.keyPassword), chain);
            if (target.exists() && !target.delete()) {
                throw new IOException("无法覆盖 " + target);
            }
            try (FileOutputStream output = new FileOutputStream(target)) {
                convertedStore.store(output, password(spec.storePassword));
            }
        } catch (IOException error) {
            throw new IOException("把 " + sourceType + " 密钥库转成 " + targetType
                    + " 失败（" + source + "，别名 " + spec.alias + "）：" + error.getMessage(), error);
        } catch (Throwable error) {
            throw new IOException("把 " + sourceType + " 密钥库转成 " + targetType
                    + " 失败（" + source + "）：" + error, error);
        }

        Spec converted = spec.copy();
        converted.type = targetType;
        converted.storePath = target.getAbsolutePath();
        converted.alias = spec.alias;
        info(log, "密钥库是 " + sourceType + "，宿主 apksigner 只认平台默认类型 "
                + targetType + "，已自动转换：" + target.getAbsolutePath() + "（别名/口令不变）");
        validate(converted, target, targetType, log);
        return converted;
    }

    /** 别名为空时自动挑第一个私钥条目（与宿主行为一致）。 */
    private static String resolveAlias(KeyStore keyStore, String alias) throws Exception {
        if (alias != null && !alias.isEmpty() && keyStore.isKeyEntry(alias)) {
            return alias;
        }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (keyStore.isKeyEntry(candidate)) {
                return candidate;
            }
        }
        throw new IOException("密钥库里没有私钥条目"
                + (alias == null || alias.isEmpty() ? "" : "（指定的别名 " + alias + "）"));
    }

    private static char[] password(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }

    private static void info(Log log, String message) {
        if (log != null) {
            log.line(message);
        }
    }
}
