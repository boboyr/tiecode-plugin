package com.goproject.plugin.task;

import com.goproject.plugin.tool.ToolchainManager;
import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.util.file.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * 把构建产物拷贝到任务输出目录，供 IDE 的构建产物视图查看。
 *
 * <p>对应结绳Linux工程插件的 {@code CopySoFiletask}。
 * 注意运行时不读这里 —— {@code SDLPluginPage} 直接加载
 * {@code <env>/linux/tmp/libmain.so}。
 */
public class PublishLibTask extends BaseTask {

    private static final String BIN_NAME = "libmain.so";

    @Override
    public void prepare(Project project, ProjectVariant variant, Logger mlogger) {
        super.prepare(project, variant, mlogger);
    }

    @Override
    public void execute() throws TaskInterruptError {
        File src = new File(ToolchainManager.tmpDir(AndroidPlugins.getContext(this)), BIN_NAME);
        if (!src.exists()) {
            throw new TaskInterruptError("未找到产物: " + src);
        }
        try {
            FileUtils.copyFile(src.getAbsolutePath(), getOutputPath(BIN_NAME));
            logger.info("已拷贝至: " + getOutputPath(BIN_NAME));
        } catch (IOException e) {
            throw new TaskInterruptError("拷贝库文件失败: " + e);
        }
    }

    @Override
    public String getId() {
        return "publishLib";
    }

    @Override
    public String getName() {
        return "拷贝库文件";
    }
}
