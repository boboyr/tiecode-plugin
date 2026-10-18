package com.goproject.plugin.project;

import java.io.File;

/**
 * 工程类别判定。
 *
 * <p>合并插件只有一个工程类型「go语言工程」，创建时通过两个模板区分：
 * <ul>
 *   <li>「控制台工程」模板：写出 {@code src/main.go} + {@code src/go.mod}；</li>
 *   <li>「SDL3工程」模板：把 {@code gosdl3_template.zip} 解到工程根目录
 *       （{@code main.go} / {@code entry.go} / {@code go.mod} / {@code vendor/}）。</li>
 * </ul>
 *
 * <p>宿主 IDE 在<b>打开工程</b>时才调用 {@link GoProject#prepare()}（此时模板文件
 * 已经写出），所以打开时按工程目录内容判定走哪套构建流水线。
 */
public final class GoProjectKind {

    private GoProjectKind() {
    }

    /** 判断工程目录是否为 SDL3 工程（用 SDL3 构建流水线），否则走控制台流水线。 */
    public static boolean isSdl3(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) {
            return false;
        }
        // 1) 模板显式写出的标记文件
        if (new File(projectDir, ".gosdl3").exists()) {
            return true;
        }
        // 2) SDL3 模板的结构特征：根目录有 entry.go（入口 //export SDL_main）
        if (new File(projectDir, "entry.go").exists()) {
            return true;
        }
        // 3) 内置 go-sdl3 绑定的 vendor 目录
        File vendor = new File(projectDir, "vendor/github.com/Zyko0");
        if (vendor.isDirectory()) {
            return true;
        }
        // 4) 根目录 main.go + go.mod（老版 SDL3 工程没有标记文件时兜底）
        return new File(projectDir, "main.go").isFile()
                && new File(projectDir, "go.mod").isFile();
    }
}
