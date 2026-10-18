package com.linux.plugin.task;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import java.io.File;
import com.rapid.util.android.EnvUtils;
import com.rapid.api.Platform;
import com.rapid.util.file.FileUtils;
import java.io.IOException;

public class CopySoFiletask extends BaseTask {
    
    private static final String BIN_NAME = "libmain.so";

    @Override
    public void execute() throws TaskInterruptError {
        File src = new File(new File(EnvUtils.getEnvironmentDir(Platform.getApplicationContext(), "linux"), "tmp"), BIN_NAME);
        if (!src.exists()) {
            throw new TaskInterruptError("未找到产物: " + src);
        }
        try {
            FileUtils.copyFile(src.getAbsolutePath(), getOutputPath(BIN_NAME));
            logger.info("已拷贝至: " + getOutputPath(BIN_NAME));
        } catch (IOException e) {
            throw new TaskInterruptError("拷贝二进制文件失败: " + e);
        }
    }

    @Override
    public String getId() {
        return "copySoFile";
    }

    @Override
    public String getName() {
        return "拷贝库文件";
    }
    
}
