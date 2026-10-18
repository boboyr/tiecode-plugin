package com.goproject.plugin.tool;
import com.rapid.api.project.toolchain.Binary;
import com.rapid.api.project.toolchain.Installable;
import com.rapid.api.project.toolchain.Tool;

public interface GoCompile extends Binary, Installable{
    int compile(GoCompileParams goCompileParams, Tool.OnExecutionCallback onExecutionCallback);
}
