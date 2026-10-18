package com.scala.android.util;

import android.content.Context;
import android.util.Log;

import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.toolchain.android.signer.ApkSigner;
import com.rapid.api.project.toolchain.android.signer.KeyStoreParams;
import com.rapid.api.project.toolchain.android.signer.KeyType;
import com.scala.android.sbt.SbtAndroidConfig;

import java.io.File;
import java.io.IOException;

/**
 * 签名配置：release 用 build.sbt 里声明的密钥库，debug（或没配密钥库）用宿主的测试证书。
 *
 * <p>对应 {@code scala-android-app-modern/build.sbt} 里的
 * <pre>
 * androidReleaseKeystore := Some(baseDirectory.value / "release-test.keystore")
 * androidReleaseKeystoreAlias := Some("release")
 * androidReleaseKeystorePassword := Some("changeit")
 * androidReleaseKeyPassword := Some("changeit")
 * </pre>
 *
 * <p><b>关键</b>：宿主的 apksigner 只支持 JKS（它自己的 {@code SignAPKTask} 里直接拒绝
 * PKCS12，而 PKCS12 会让 {@code ApkSignerTool.main} 走到 {@code System.exit} →
 * 表现出来是<b>结绳闪退</b>）。所以这里统一走 {@link KeystoreTool#normalize}：
 * 先把密钥库打开、把私钥取出来做校验，非 JKS 就转成 JKS 缓存副本，再交给宿主签名。
 */
public final class KeystoreResolver {

    private static final String TAG = "KeystoreResolver";

    /** 插件自带的测试密钥库（PKCS12，与早期模板的 JKS 是同一把密钥）。 */
    private static final String BUNDLED_TEST_KEYSTORE = "toolchain/legacy-test-keystore.p12";
    /** 早期（≤1.6）模板发出去的 JKS 测试密钥库的 SHA-256：命中就自动换成上面的 PKCS12。 */
    private static final String LEGACY_JKS_SHA256 =
            "92dc086ab4e3d0cfc65981fcc29792ffa1c4405e9b808b6952c06495a0be674f";

    private KeystoreResolver() {
    }

    /**
     * 宿主 {@code Tool.APK_SIGNER} 兜底路径用：它不传 {@code --ks-type}，
     * 只能吃"平台默认类型"的密钥库，所以把用户密钥库转成那个类型的副本。
     */
    public static KeyStoreParams forHostSigner(KeyStoreParams params, Context context,
                                               KeystoreTool.Log log) throws IOException {
        if (params == null || params.type == KeyType.X509) {
            return params;
        }
        KeystoreTool.Spec spec = new KeystoreTool.Spec();
        spec.type = params.type == KeyType.PKCS12 ? "PKCS12" : "JKS";
        spec.storePath = params.storePath;
        spec.alias = params.keyAliasOrX509Path;
        spec.storePassword = params.storePassword;
        spec.keyPassword = params.keyPassword;
        spec.cacheDir = context == null ? null
                : new File(context.getFilesDir(), "env/scala-android/keystore");
        KeystoreTool.Spec normalized = KeystoreTool.normalize(spec, log);
        KeyStoreParams result = new KeyStoreParams();
        result.type = "X509".equalsIgnoreCase(normalized.type) ? KeyType.X509
                : ("PKCS12".equalsIgnoreCase(normalized.type) ? KeyType.PKCS12 : KeyType.JKS);
        result.storePath = normalized.storePath;
        result.keyAliasOrX509Path = normalized.alias == null ? "" : normalized.alias;
        result.storePassword = normalized.storePassword == null ? "" : normalized.storePassword;
        result.keyPassword = normalized.keyPassword == null ? "" : normalized.keyPassword;
        return result;
    }

    /**
     * 把"早期模板发的 JKS 测试密钥库"换成插件自带的 PKCS12 副本（同一把密钥）。
     *
     * @return 可用的签名参数；PKCS12 也不可用时返回 null（调用方继续走测试证书兜底）
     */
    private static KeyStoreParams migrateLegacyTestKeystore(KeystoreTool.Spec spec, Context context,
                                                            KeystoreTool.Log log) {
        if (context == null || !KeystoreTool.isTypeAvailable("PKCS12")) {
            return null;
        }
        try {
            File cacheDir = new File(context.getFilesDir(), "env/scala-android/keystore");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
                return null;
            }
            File target = new File(cacheDir, "release-test.keystore.p12");
            if (!target.isFile() || target.length() <= 0) {
                try (java.io.InputStream input = context.getAssets().open(BUNDLED_TEST_KEYSTORE);
                     java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }
            }
            KeystoreTool.Spec migrated = spec.copy();
            migrated.type = "PKCS12";
            migrated.storePath = target.getAbsolutePath();
            KeystoreTool.validateWith(migrated, "PKCS12", message -> {
                Log.i(TAG, message);
                if (log != null) {
                    log.line(message);
                }
            });
            String message = "工程里的 release-test.keystore 是早期模板生成的 JKS，"
                    + "本机 Android 没有 JKS 实现；已自动换成插件自带的同一把密钥的 PKCS12 副本："
                    + target.getAbsolutePath() + "（签名身份不变，无需改工程）";
            Log.i(TAG, message);
            if (log != null) {
                log.line(message);
            }
            KeyStoreParams params = new KeyStoreParams();
            params.type = KeyType.PKCS12;
            params.storePath = migrated.storePath;
            params.keyAliasOrX509Path = migrated.alias;
            params.storePassword = migrated.storePassword;
            params.keyPassword = migrated.keyPassword;
            return params;
        } catch (Throwable error) {
            Log.w(TAG, "迁移早期模板密钥库失败", error);
            if (log != null) {
                log.line("! 迁移早期模板密钥库失败：" + error);
            }
            return null;
        }
    }

    /** 用宿主的测试证书（X509：pk8 + x509，走 apksigner 的 --key/--cert，不碰密钥库）。 */
    private static KeyStoreParams testKey(ApkSigner signer, Context context,
                                          KeystoreTool.Log log, String why) {
        if (signer == null) {
            return null;
        }
        try {
            if (context != null && !signer.isInstalled(context)) {
                signer.install(context);
            }
            KeyStoreParams params = signer.testKey().get().toStoreParams();
            String message = "使用宿主测试证书（" + why + "）";
            Log.i(TAG, message);
            if (log != null) {
                log.line(message);
            }
            return params;
        } catch (Throwable error) {
            Log.w(TAG, "获取测试签名证书失败", error);
            if (log != null) {
                log.line("! 获取宿主测试证书失败：" + error);
            }
            return null;
        }
    }

    /** 解析出本次变体要用的签名参数；拿不到时返回 null（调用方报错）。 */
    public static KeyStoreParams resolve(SbtAndroidConfig config, ProjectVariant variant,
                                         ApkSigner signer, Context context) {
        return resolve(config, variant, signer, context, null);
    }

    /**
     * @param log 构建日志回调；传 null 时只写 logcat
     */
    public static KeyStoreParams resolve(SbtAndroidConfig config, ProjectVariant variant,
                                         ApkSigner signer, Context context, KeystoreTool.Log log) {
        boolean release = variant != null && variant.isRelease();
        boolean custom = release && config != null && config.releaseKeystore != null
                && config.releaseKeystore.isFile();
        if (!custom) {
            return testKey(signer, context, log, release
                    ? "release 变体没有配置自定义密钥库"
                    : "debug 变体使用宿主测试证书");
        }

        // 自定义密钥库：先看本运行时支不支持它的类型
        KeystoreTool.Spec spec = new KeystoreTool.Spec();
        spec.storePath = config.releaseKeystore.getAbsolutePath();
        spec.alias = config.releaseKeystoreAlias == null ? "" : config.releaseKeystoreAlias;
        spec.storePassword = config.releaseKeystorePassword == null
                ? "" : config.releaseKeystorePassword;
        spec.keyPassword = config.releaseKeyPassword == null
                ? spec.storePassword : config.releaseKeyPassword;
        spec.type = KeystoreTool.typeByExtension(config.releaseKeystore);
        spec.cacheDir = context == null ? null
                : new File(context.getFilesDir(), "env/scala-android/keystore");

        String type = KeystoreTool.effectiveType(spec);
        spec.type = type;
        String capability = "本机 Android 密钥库实现：" + KeystoreTool.describeAvailableTypes();
        Log.i(TAG, capability);
        if (log != null) {
            log.line(capability);
        }

        // 类型不可用（Android 上最常见的是 "JKS not found"）→ 读不了工程里的密钥库。
        // 这时不能把坏参数丢给 apksigner（它会 System.exit 让结绳闪退），
        // 而是退回宿主测试证书，保证仍能产出可安装的 APK，并把原因写清楚。
        if (!KeystoreTool.isTypeAvailable(type)) {
            // 特例：工程里的密钥库就是早期模板发的那个 JKS 测试密钥库 →
            // 插件自带同一把密钥的 PKCS12 副本，直接换过来（签名身份不变）。
            if ("JKS".equalsIgnoreCase(type)
                    && LEGACY_JKS_SHA256.equalsIgnoreCase(KeystoreTool.sha256(
                            new File(spec.storePath)))) {
                KeyStoreParams migrated = migrateLegacyTestKeystore(spec, context, log);
                if (migrated != null) {
                    return migrated;
                }
            }
            String reason = "工程里的签名密钥库是 " + type + " 格式，但本次运行的 Android "
                    + "没有 " + type + " 密钥库实现（" + KeystoreTool.describeAvailableTypes()
                    + "），无法读取 " + spec.storePath;
            Log.w(TAG, reason);
            if (log != null) {
                log.line("! " + reason);
            }
            return testKey(signer, context, log,
                    "已改用它签名（release 变体与 debug 用同一张宿主测试证书）。"
                            + "长期使用请把密钥库换成本机支持的格式（一般 PKCS12 可用），"
                            + "或改用 pk8 + x509 证书（build.sbt 里配 androidReleaseKeystore 指向 .pk8）");
        }

        // 只校验，不做转换：真正签名时会带上 --ks-type 明确告诉 apksigner 用哪种类型
        // （宿主的 AndroidApkSigner 不传 --ks-type，所以才需要猜平台默认类型）。
        try {
            KeystoreTool.validateWith(spec, type, message -> {
                Log.i(TAG, message);
                if (log != null) {
                    log.line(message);
                }
            });
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }

        KeyStoreParams params = new KeyStoreParams();
        params.type = "PKCS12".equalsIgnoreCase(spec.type) ? KeyType.PKCS12
                : ("X509".equalsIgnoreCase(spec.type) ? KeyType.X509 : KeyType.JKS);
        params.storePath = spec.storePath;
        params.keyAliasOrX509Path = spec.alias == null ? "" : spec.alias;
        params.storePassword = spec.storePassword == null ? "" : spec.storePassword;
        params.keyPassword = spec.keyPassword == null ? "" : spec.keyPassword;

        String summary = "使用 build.sbt 声明的签名密钥库：" + params.storePath
                + "（" + params.type + "，别名 " + params.keyAliasOrX509Path + "）";
        Log.i(TAG, summary);
        if (log != null) {
            log.line(summary);
        }
        return params;
    }
}
