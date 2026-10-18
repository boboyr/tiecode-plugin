package com.linux.plugin.tool;
import cn.tiecode.api.tool.Gxx;
import com.rapid.api.project.toolchain.Tool.OnExecutionCallback;
import java.util.List;
import cn.tiecode.api.tool.GxxCompileParams;
import android.content.Context;
import com.rapid.api.project.toolchain.Tool;
import java.io.IOException;
import com.rapid.api.project.toolchain.LocalBinary;
import java.io.File;
import com.rapid.util.android.EnvUtils;
import com.rapid.api.ext.plugin.AndroidPlugins;
import java.util.ArrayList;
import java.util.Collections;
import com.rapid.api.Platform;

public class GxxCompile extends LocalBinary implements Gxx {

	/** OSS 里的工具链压缩包对象名。 */
	private static final String TOOLCHAIN_TAR_XZ = "gcc-17.0.0-android-aarch64.tar.xz";

	public GxxCompile(){
		super(a() + "/gcc-17.0.0/bin/g++");
	}

	private static File a() {
        return EnvUtils.getEnvironmentDir(AndroidPlugins.getContext(GxxCompile.class), "linux");
    }
	/*
	@Override
	public int execute(List<String> args, Tool.OnExecutionCallback callback) {
		return 0;
	}
*/
	@Override
	public void install(Context context) throws IOException {
		// 环境目录:压缩包下载到此,解压后得到 gcc-17.0.0/ 工具链。
		File envDir = a();
		File tarXz = new File(envDir, TOOLCHAIN_TAR_XZ);

		// 1. 先用阿里云 OSS SDK 下载工具链压缩包到 a()。
		OssDownloader.download(context, TOOLCHAIN_TAR_XZ, tarXz);

		// 2. 再解压到 a(),得到 gcc-17.0.0/bin/g++。
		TarXzUtils.extractTarXz(tarXz.getAbsolutePath(), envDir.getAbsolutePath());
	}

	@Override
	public boolean isInstalled(Context context) {
		return new File(a(), "gcc-17.0.0/bin/g++").exists();
	}

	@Override
	public int compile(GxxCompileParams params, Tool.OnExecutionCallback callback) {
		ArrayList arrayList = new ArrayList();
		String[] strArr = params.options;
        if (strArr != null) {
            Collections.addAll(arrayList, strArr);
        }
		// 必须带 -o,否则 g++ 退化为默认输出 a.out(当前工作目录只读,必然失败)。
		arrayList.add("-o");
		arrayList.add(params.outputPath);
		arrayList.addAll(params.inputFiles);
		// 打印完整命令行,便于确认编译是否真正执行、参数是否正确。
		
		try {
			Platform.getLogger().info("gxx 命令: " + getBinaryPath() + " " + joinArgs(arrayList));
		} catch (Throwable ignored) {
		}
		
        return execute(arrayList, callback);
	}

	/** 把参数拼成一行,便于日志查看。 */
	private static String joinArgs(java.util.List args) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < args.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(args.get(i));
		}
		return sb.toString();
	}

}
