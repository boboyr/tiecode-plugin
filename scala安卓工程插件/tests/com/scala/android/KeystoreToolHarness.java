package com.scala.android;

import com.scala.android.util.KeystoreTool;

import java.io.File;

/**
 * 密钥库归一化自检（纯 JVM）：把 PKCS12 按"目标类型 = JKS"归一化，验证能转出 JKS
 * 且能用同样的别名/口令取到私钥与证书链。
 *
 * <p>模拟设备情形：宿主 apksigner 按 {@code KeyStore.getDefaultType()}（Android 上回退 JKS）
 * 加载，而工程里的密钥库是 PKCS12 → 加载失败 → apksigner {@code System.exit} → 结绳闪退。
 */
public final class KeystoreToolHarness {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("用法: KeystoreToolHarness <keystore> <storePass> <keyPass> <alias> [targetType]");
            return;
        }
        File source = new File(args[0]);
        KeystoreTool.Spec spec = new KeystoreTool.Spec();
        spec.storePath = source.getAbsolutePath();
        spec.storePassword = args[1];
        spec.keyPassword = args[2];
        spec.alias = args[3];
        spec.type = KeystoreTool.typeByExtension(source);
        spec.cacheDir = new File("/tmp/keystore-cache");

        String targetType = args.length > 4 ? args[4] : KeystoreTool.defaultKeystoreType();
        System.out.println("源密钥库   : " + source.getName()
                + "（嗅探类型 " + KeystoreTool.sniffType(source) + "）");
        System.out.println("平台默认类型: " + KeystoreTool.defaultKeystoreType());
        System.out.println("目标类型   : " + targetType);

        KeystoreTool.Spec normalized = KeystoreTool.normalize(spec, System.out::println, targetType);
        System.out.println("归一化结果 : " + normalized);

        File produced = new File(normalized.storePath);
        boolean ok = produced.isFile() && produced.length() > 0
                && normalized.alias != null && !normalized.alias.isEmpty();
        System.out.println(ok ? "== 密钥库归一化自检通过" : "== 密钥库归一化自检失败");
        if (!ok) {
            System.exit(3);
        }
    }
}
