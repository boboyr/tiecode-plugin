package com.goproject.plugin.structure;

import com.rapid.api.project.structure.PSFile;

import java.io.File;

/**
 * 带自定义显示名的文件树节点。
 *
 * <p>用于「外部库」下的虚拟文件夹：包装的是<b>外部真实目录</b>
 * （如 GOROOT/src、模块缓存），但显示名与目录名不同——
 * 例如把 {@code …/go/src} 显示成「GO SDK 1.27.1」。
 *
 * <p>子节点仍按真实目录内容列出（PSFile 的常规展开），
 * 所以 SDK 源码和依赖包能像 GoLand 的 External Libraries 一样逐层浏览。
 */
public class NamedPSFile extends PSFile {

    private final String displayName;

    public NamedPSFile(File file, String displayName) {
        super(file);
        this.displayName = displayName;
    }

    @Override
    public String getName() {
        return displayName != null ? displayName : super.getName();
    }
}
