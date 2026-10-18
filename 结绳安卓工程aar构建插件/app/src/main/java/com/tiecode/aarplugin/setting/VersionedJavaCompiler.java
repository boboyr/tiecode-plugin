package com.tiecode.aarplugin.setting;

import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.java.JavaCompiler;
import com.rapid.api.project.toolchain.java.JavacParams;

/**
 * 只做一件事的 javac 装饰器：把 {@code -source/-target} 换成设置里选的版本，
 * 真正的编译依旧交给宿主原来的编译器（不改它的实现，所以不挑宿主用的是
 * nb-javac 还是外部 javac）。
 */
public class VersionedJavaCompiler implements JavaCompiler {

    private final JavaCompiler delegate;
    private final String version;

    public VersionedJavaCompiler(JavaCompiler delegate, String version) {
        this.delegate = delegate;
        this.version = version;
    }

    /** 宿主 compileWithJavac 会拿这个值当 sourceVersion/targetVersion。 */
    @Override
    public String getCompilerVersion() {
        return version;
    }

    @Override
    public boolean compile(JavacParams params, Tool.OnExecutionCallback callback) {
        if (params != null) {
            params.sourceVersion = version;
            params.targetVersion = version;
        }
        return delegate.compile(params, callback);
    }
}
