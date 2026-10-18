package com.goproject.plugin;

import com.goproject.plugin.lsp.GoplsConfig;
import com.goproject.plugin.lsp.GoplsToolchain;

import java.io.File;

/**
 * 控制台（终端）里执行 go 命令时的环境变量前缀。
 *
 * <p>宿主控制台里 {@code openConsole(...)} 的命令串不带环境变量，go 的默认行为：
 * <ul>
 *   <li>{@code GOCACHE} 依赖 {@code HOME}——控制台里 HOME 常不可写，
 *       {@code go run} 会直接报 {@code GOCACHE is not defined}；</li>
 *   <li>{@code GOPROXY} 未设时走官方默认 {@code proxy.golang.org}，
 *       部分网络下 {@code dial tcp ... i/o timeout}，依赖永远下不下来。</li>
 * </ul>
 *
 * <p>这里导出的环境与 {@link GoplsToolchain#buildEnvironment} 的值保持一致：
 * 缓存都落在 app 私有目录的同一份里，控制台里 {@code go get} 下载的依赖，
 * gopls 补全（扫描模块缓存）立刻可见。
 *
 * <p>用法：{@code openConsole("dalvikvm;clear;" + GoEnv.consoleEnvPrefix() + "cd <src>;<go> get ...")}。
 */
public final class GoEnv {

    private GoEnv() {
    }

    /** 生成 {@code export X=...;} 形式的环境变量命令串（紧跟 go 命令之前）。 */
    public static String consoleEnvPrefix() {
        File env = GoplsToolchain.environmentDir(GoplsToolchain.pluginContext());
        File goroot = GoplsToolchain.goRoot(GoplsToolchain.pluginContext());

        return "export GOROOT=" + goroot.getAbsolutePath() + ";"
                + "export GOPATH=" + new File(env, "gopath").getAbsolutePath() + ";"
                + "export GOMODCACHE=" + new File(env, "gopath/pkg/mod").getAbsolutePath() + ";"
                + "export GOCACHE=" + new File(env, "gocache").getAbsolutePath() + ";"
                + "export GOTMPDIR=" + new File(env, "gotmp").getAbsolutePath() + ";"
                // 模块代理：默认 https://goproxy.cn,direct（官方源不可达时回退直连），
                // 解决 go get 报 proxy.golang.org i/o timeout。
                + "export GOPROXY=" + GoplsConfig.getGoProxy() + ";"
                + "export GOFLAGS=-mod=mod;"
                + "export GOTOOLCHAIN=local;";
    }
}
