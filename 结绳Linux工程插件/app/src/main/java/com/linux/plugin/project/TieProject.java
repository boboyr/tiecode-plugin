package com.linux.plugin.project;

import com.linux.plugin.running.CopySoFileRunc;
import com.linux.plugin.running.TieRunConfiguration;
import com.linux.plugin.task.CompileWithGxxTask;
import com.linux.plugin.task.TieCompileTask;

import com.rapid.api.project.CodeProject;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.Task;
import com.linux.plugin.task.CopySoFiletask;

/**
 * 结绳 Linux 工程。
 *
 * <p>装配完整的构建流水线:
 * 结绳源文件编译(compileWithTieclinux)→ C++ 编译(compileWithGxx)→ 拷贝二进制(copyBinFile)。
 */
public class TieProject extends CodeProject {

    @Override
    public void prepare() {
        Task compileWithTie = new TieCompileTask();
        Task compileWithGxx = new CompileWithGxxTask();
        Task copyBinFile = new CopySoFiletask();

        addTask(compileWithGxx);
        addTask(compileWithTie);
        addTask(copyBinFile);

        compileWithGxx.dependsOn(compileWithTie);
        copyBinFile.dependsOn(compileWithGxx);

        addRunConfiguration(new TieRunConfiguration());
        addRunConfiguration(new CopySoFileRunc());
    }

    @Override
    public ProjectVariant getVariant() {
        return ProjectVariant.DEBUG;
    }
}
