package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.tool.ScalaToolchain;

import java.io.File;
import java.util.List;

/**
 * 「清理构建产物」：删掉 {@code build/} 与补全/解析的中间产物。
 */
public class CleanScalaAndroidTask extends ScalaAndroidTask {

    public static final String ID = "cleanScalaAndroid";
    public static final String NAME = "清理构建产物";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        alwaysRun();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void execute() throws TaskInterruptError {
        ScalaAndroidProject scalaProject = requireProject();
        File build = scalaProject.buildDir();
        deleteRecursively(build);
        scalaProject.setResolution(null);
        info("已删除 " + build);
    }
}
