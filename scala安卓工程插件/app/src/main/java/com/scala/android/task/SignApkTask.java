package com.scala.android.task;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.signer.ApkSigner;
import com.rapid.api.project.toolchain.android.signer.KeyStoreParams;
import com.rapid.api.project.toolchain.android.signer.KeyType;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.util.KeystoreResolver;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 「签名APK」：用宿主的 apksigner 给对齐后的 APK 做 v1+v2+v3 签名。
 *
 * <h3>为什么不直接调 {@code Tool.APK_SIGNER}</h3>
 * 宿主的 {@code AndroidApkSigner} 拼的命令是
 * <pre>
 * sign --in … --out … --ks … --ks-key-alias … --ks-pass … --key-pass …
 * </pre>
 * <b>没有 {@code --ks-type}</b>。而它内置的 apksig（{@code com.android.apksigner.SignerParams}）
 * 在没有 {@code --ks-type} 时会用 {@code KeyStore.getDefaultType()} 去加载密钥库 ——
 * 平台默认类型与工程里密钥库的真实类型不一致时，加载失败后
 * {@code ApkSignerTool.main} 会直接 {@code System.exit}，表现成<b>结绳闪退</b>。
 *
 * <p>所以这里改成：<b>自己按同一套参数调用那个 apksigner，但补上 {@code --ks-type}</b>
 * （类型由 {@link com.scala.android.util.KeystoreTool} 嗅探文件头得到，并且已用同类型 +
 * 同口令预校验过，加载必然成功）。拿不到那个类时（老版本宿主）再退回
 * {@code Tool.APK_SIGNER}，并把密钥库转成平台默认类型的副本交给它。
 */
public class SignApkTask extends ScalaAndroidTask {

    public static final String ID = "signApkScala";
    public static final String NAME = "签名APK";

    /** 宿主内置 apksigner 的入口类（在宿主 dex 里，插件通过父类加载器可见）。 */
    private static final String APKSIGNER_CLASS = "com.android.apksigner.ApkSignerTool";

    @Override
    public void prepare(com.rapid.api.project.Project p,
                        com.rapid.api.project.ProjectVariant variant,
                        com.rapid.api.logging.Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new ArrayList<>();
        if (project != null) {
            com.rapid.api.project.task.Task zipAlign = project.getTasks().getTask(ZipAlignApkTask.ID);
            if (zipAlign != null) {
                inputs.add(new File(zipAlign.getIntermediatePath(project.getName()
                        + "-aligned.apk")));
            }
            if (project.getAndroidConfig() != null) {
                // 换密钥库/密码也要重新签
                inputs.add(project.getAndroidConfig().releaseKeystore);
            }
        }
        decideState(inputs, new File(getIntermediatePath(project == null
                ? "app-signed.apk" : project.getName() + "-signed.apk")), true);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void execute() throws TaskInterruptError {
        ScalaAndroidProject scalaProject = requireProject();
        SbtAndroidConfig config = scalaProject.getAndroidConfig();
        ApkSigner signer = getTool(Tool.APK_SIGNER);
        if (signer == null) {
            throw fail("宿主工具链里没有 APK 签名器");
        }

        KeyStoreParams params;
        try {
            params = KeystoreResolver.resolve(config, variant, signer,
                    AndroidPlugins.getContext(this), message -> info(message));
        } catch (Throwable error) {
            // 参数有问题就提前失败：宿主的 apksigner 遇到坏参数会 System.exit（结绳闪退）
            throw fail("签名配置不可用，已阻止调用 apksigner（它失败会让结绳直接退出）："
                    + error.getMessage());
        }
        if (params == null) {
            throw fail("无法获取签名证书（既没有 build.sbt 的密钥库，也拿不到测试证书）");
        }

        File input = new File(project.getTasks().getTask(ZipAlignApkTask.ID)
                .getIntermediatePath(project.getName() + "-aligned.apk"));
        if (!input.isFile()) {
            throw fail("没有待签名的 APK：" + input);
        }
        File output = new File(getIntermediatePath(project.getName() + "-signed.apk"));
        mkdirs(output.getParentFile());
        if (output.exists() && !output.delete()) {
            warn("无法删除旧的签名产物 " + output);
        }

        info("证书 " + params.storePath + " · 类型 " + params.type + " · 别名 "
                + (params.keyAliasOrX509Path == null || params.keyAliasOrX509Path.isEmpty()
                ? "(自动)" : params.keyAliasOrX509Path));

        // 1) 首选：自己调用宿主内置的 apksigner，带上 --ks-type
        String error = signWithExplicitType(params, input, output);
        if (error == null && output.isFile() && output.length() > 0) {
            info("签名完成 " + output.getName() + "（" + output.length() + " bytes，v1/v2/v3）");
            return;
        }
        if (error != null) {
            warn("直接调用 apksigner 失败（" + error + "），改用宿主 Tool.APK_SIGNER");
        }

        // 2) 兜底：宿主的 Tool.APK_SIGNER（不传 --ks-type，所以先转成平台默认类型的副本）
        try {
            KeyStoreParams hostParams = KeystoreResolver.forHostSigner(params,
                    AndroidPlugins.getContext(this), message -> info(message));
            if (hostParams == null) {
                throw fail("无法准备宿主签名器所需的密钥库");
            }
            if (output.exists() && !output.delete()) {
                warn("无法删除旧的签名产物 " + output);
            }
            signer.signApk(hostParams, input.getAbsolutePath(), output.getAbsolutePath());
        } catch (TaskInterruptError interrupt) {
            throw interrupt;
        } catch (Throwable throwable) {
            throw fail("签名 APK 失败：" + throwable);
        }
        if (!output.isFile() || output.length() <= 0) {
            throw fail("签名没有产出文件：" + output);
        }
        info("签名完成 " + output.getName() + "（" + output.length() + " bytes）");
    }

    /**
     * 用宿主内置的 apksigner 签名，命令补上 {@code --ks-type}。
     *
     * @return null 表示成功；否则返回失败原因（调用方会退回宿主工具）
     */
    private String signWithExplicitType(KeyStoreParams params, File input, File output) {
        try {
            Class<?> tool = Class.forName(APKSIGNER_CLASS);
            Method main = tool.getMethod("main", String[].class);

            List<String> args = new ArrayList<>();
            args.add("sign");
            args.add("--in");
            args.add(input.getAbsolutePath());
            args.add("--out");
            args.add(output.getAbsolutePath());
            if (params.type == KeyType.X509) {
                args.add("--key");
                args.add(params.storePath);
                args.add("--cert");
                args.add(params.keyAliasOrX509Path);
            } else {
                String keyStoreType = params.type == KeyType.PKCS12 ? "PKCS12" : "JKS";
                args.add("--ks");
                args.add(params.storePath);
                // 关键：宿主工具从不传这个参数，于是 apksig 只好猜平台默认类型
                args.add("--ks-type");
                args.add(keyStoreType);
                args.add("--ks-key-alias");
                args.add(params.keyAliasOrX509Path);
                args.add("--ks-pass");
                args.add("pass:" + params.storePassword);
                args.add("--key-pass");
                args.add("pass:" + params.keyPassword);
            }
            info("apksigner sign " + (params.type == KeyType.X509
                    ? "（X509：--key/--cert）"
                    : "--ks … --ks-type " + (params.type == KeyType.PKCS12 ? "PKCS12" : "JKS"))
                    + " · 输出 " + output.getName());
            main.invoke(null, (Object) args.toArray(new String[0]));
            return output.isFile() && output.length() > 0 ? null : "apksigner 没有产出文件";
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }
}
