package com.scala.android.tool;

import android.content.Context;

import com.rapid.api.project.toolchain.Tool;

import java.io.IOException;

/**
 * Scala 编译器工具链组件。
 *
 * <p>与 go 控制台插件里的 {@code GoCompile} 同构：宿主通过
 * {@code ToolchainService#getTool(Key)} 拿到它，再调用 {@link #compile}。
 * 区别只在于 Go 的编译器是外部可执行文件（{@code LocalBinary}），
 * 而 Scala 编译器是随插件一起打包的 Java 库，直接在宿主进程内调用
 * （{@code dotty.tools.dotc.Main}）。
 */
public interface ScalaCompile extends Tool {

    /**
     * 编译 Scala 源文件到 class 目录。
     *
     * @param params   编译参数
     * @param callback 编译日志回调，可为 null
     * @return 0 表示成功，非 0 表示失败
     */
    int compile(ScalaCompileParams params, OnExecutionCallback callback);

    /**
     * 编译器随插件打包，永远"已安装"。
     */
    boolean isInstalled(Context context);

    /**
     * 与 {@link #isInstalled(Context)} 对应，这里不需要安装动作。
     */
    void install(Context context) throws IOException;
}
