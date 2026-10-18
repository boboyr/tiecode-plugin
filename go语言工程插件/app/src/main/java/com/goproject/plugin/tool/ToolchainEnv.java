package com.goproject.plugin.tool;

import android.content.Context;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 组装 go build 需要的环境变量。
 *
 * <p>这里每一条都有原因，别随手删：
 * <ul>
 *   <li>{@code PATH} 里必须有 {@code go/bin}（go 会去调自己带的编译/链接工具）；</li>
 *   <li>{@code GOROOT} 指向解压出来的发行版，否则 go 会去猜路径而失败；</li>
 *   <li>{@code GOTOOLCHAIN=local}：不设的话，go.mod 里声明的版本比本机高时
 *       go 会尝试联网下载另一套工具链，离线/国内网络下直接报错；</li>
 *   <li>{@code GOPROXY=off}：purego 用工程内的 {@code third_party/purego} 替换，
 *       整个编译不需要联网 —— 设成 off 可以在万一需要下载时立刻失败，而不是卡住；</li>
 *   <li>{@code CGO_ENABLED=1} + {@code CC}/{@code CXX}：Android 上 purego 必须走 cgo，
 *       且 c-shared 必须外部链接，所以一定要指到 Android GCC；</li>
 *   <li>{@code GOOS=android}/{@code GOARCH=arm64}：和宿主的 ABI 对齐；</li>
 *   <li>GOPATH/GOCACHE/GOTMPDIR/HOME 全部落在应用私有目录，保证可写。</li>
 * </ul>
 */
public final class ToolchainEnv {

    private ToolchainEnv() {
    }

    public static Map<String, String> build(Context context, File go, File gcc, File gxx) {
        File env = ToolchainManager.envDir(context);
        File goRoot = ToolchainManager.goRoot(context);
        File gccRoot = ToolchainManager.gccRoot(context);

        File gopath = new File(env, "gopath");
        File gocache = new File(env, "gocache");
        File gotmp = new File(env, "gotmp");
        ToolchainManager.ensureDir(gopath);
        ToolchainManager.ensureDir(gocache);
        ToolchainManager.ensureDir(gotmp);

        Map<String, String> map = new HashMap<>();

        String oldPath = System.getenv("PATH");
        StringBuilder path = new StringBuilder();
        path.append(new File(goRoot, "bin").getAbsolutePath()).append(File.pathSeparator);
        path.append(new File(gccRoot, "bin").getAbsolutePath()).append(File.pathSeparator);
        if (oldPath != null && oldPath.length() > 0) {
            path.append(oldPath);
        }
        map.put("PATH", path.toString());

        map.put("GOROOT", goRoot.getAbsolutePath());
        map.put("GOPATH", gopath.getAbsolutePath());
        map.put("GOCACHE", gocache.getAbsolutePath());
        map.put("GOTMPDIR", gotmp.getAbsolutePath());
        map.put("GOTOOLCHAIN", "local");
        // 模板工程自带 vendor/（go-sdl3 + purego + purego-gen + x/sys 的必要包），
        // 用 -mod=vendor 后整个编译过程不需要联网。
        map.put("GOFLAGS", "-mod=vendor");
        map.put("GOPROXY", "off");
        map.put("GOSUMDB", "off");
        map.put("GONOSUMDB", "*");

        map.put("GOOS", "android");
        map.put("GOARCH", "arm64");
        map.put("CGO_ENABLED", "1");
        map.put("CC", gcc.getAbsolutePath());
        map.put("CXX", gxx.getAbsolutePath());

        // 让临时文件和 HOME 都落在私有目录
        map.put("HOME", env.getAbsolutePath());
        map.put("TMPDIR", gotmp.getAbsolutePath());

        return map;
    }
}
