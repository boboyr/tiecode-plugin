package com.tiecode.aarplugin.compiler;

import android.util.Log;

import com.tiecode.aarplugin.AarBuildPlugin;
import com.tiecode.compiler.api.Compiler;
import com.tiecode.compiler.api.plugin.CompilerPlugin;
import com.tiecode.compiler.api.process.NameGenerator;
import com.tiecode.compiler.toolchain.CompilerPlugins;
import com.tiecode.compiler.toolchain.env.Context;
import com.tiecode.compiler.toolchain.env.Options;
import com.tiecode.compiler.util.Names;
import com.tiecode.compiler.util.PinYinUtils;

/**
 * 结绳编译器插件：在宿主创建编译器时（{@code Plugins.applyCompilers}）把
 * {@link OriginalNameGenerator} 注册进编译上下文。
 *
 * <p>宿主 {@code TiecodeCompiler.compile()} 的顺序是
 * {@code beforeCompile(...)} → {@code Plugins.applyCompilers(this)}，
 * 而 {@code Context.register} 就是 {@code Map.put}，所以这里注册的生成器会覆盖
 * {@code AndroidNameGenerator.instance(context)} 创建出来的默认随机生成器。
 *
 * <p>注册进去之前，按宿主自己的做法把「应用包名 → 拼音」塞进生成器缓存：
 * 结绳工程的应用包名常常是中文（{@code app_pkg = 我的.安卓.应用}），
 * 直接当 Java 包名/aapt2 的 package 是非法的，宿主也是用拼音让它变 ASCII。
 * 各绳包的包名（如 {@code 结绳.安卓}）则保留原样。
 */
public class OriginalNameCompilerPlugin implements CompilerPlugin {

    /** 只装配一次（Plugins 里是 HashSet，重复装配没有意义）。 */
    private static boolean assembled = false;

    public static synchronized void install() {
        if (assembled) {
            return;
        }
        assembled = true;
        CompilerPlugins.assemble(new OriginalNameCompilerPlugin());
        Log.i(AarBuildPlugin.TAG, "已向结绳编译器装配「原名输出」插件");
    }

    @Override
    public void apply(Compiler compiler) {
        if (!OriginalNameSwitch.isEnabled()) {
            // 没点「构建aar」就完全不干预宿主，宿主继续用默认随机命名
            return;
        }
        try {
            Context context = compiler.getContext();
            OriginalNameGenerator generator = new OriginalNameGenerator();
            Options options = context.get(Options.key);
            if (options != null && options.namespace != null && !options.namespace.isEmpty()) {
                generator.putCacheName(Names.of(options.namespace),
                        PinYinUtils.getPinYin(options.namespace));
            }
            context.register(NameGenerator.key, generator);
            Log.i(AarBuildPlugin.TAG, "本次编译已切换为原名输出(friendlyName=ORIGINAL)");
        } catch (Throwable t) {
            Log.w(AarBuildPlugin.TAG, "切换原名输出失败，将退回宿主默认命名", t);
        }
    }
}
