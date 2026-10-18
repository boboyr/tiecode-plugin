package com.scala.android.project.option;

import com.rapid.api.ext.framework.BaseObject;
import com.rapid.api.project.structure.PSCheckedNewChildObject;
import com.rapid.api.project.structure.PSFile;
import com.rapid.api.project.structure.extension.PSObjectCreationForm;
import com.rapid.api.project.structure.extension.PSObjectCreationOption;
import com.rapid.framework.data.form.Form;
import com.rapid.util.file.FileUtils;
import com.scala.android.project.ScalaSources;

import java.io.File;
import java.io.IOException;

/**
 * 新建文件选项基类：宿主会按 {@link #getForm()} 弹出"输入文件名"对话框，
 * 再回调 {@link #create}。
 */
public abstract class ScalaCreationOption extends BaseObject implements PSObjectCreationOption {

    /** 从表单取文件名并按需补后缀；非法返回 null。 */
    protected String fileName(Form form, String suffix) {
        String name = PSObjectCreationForm.getFileName(form);
        if (name == null) {
            return null;
        }
        name = name.trim();
        if (name.isEmpty()) {
            return null;
        }
        if (suffix != null && !name.endsWith(suffix)) {
            name = name + suffix;
        }
        return name;
    }

    protected File parentDir(PSCheckedNewChildObject parent) {
        if (parent instanceof PSFile) {
            return ((PSFile) parent).getFile();
        }
        return null;
    }

    protected void createTextFile(PSCheckedNewChildObject parent, Form form, String suffix,
                                  String content, OnCreatePSObjectListener listener) {
        File dir = parentDir(parent);
        if (dir == null || !dir.isDirectory()) {
            listener.onFail("无法定位目标目录");
            return;
        }
        String name = fileName(form, suffix);
        if (name == null) {
            listener.onFail("文件名不能为空");
            return;
        }
        File file = new File(dir, name);
        if (file.exists()) {
            listener.onFail("文件已存在: " + name);
            return;
        }
        try {
            FileUtils.writeFile(content == null ? "" : content, file);
            listener.onSuccess(new PSFile(file));
        } catch (IOException error) {
            listener.onFail("创建文件失败: " + error.getMessage());
        }
    }

    /** 按目录推导的 {@code package x.y} 头（在 src 根目录下则为空串）。 */
    protected String packageHeader(File dir) {
        String packageName = ScalaSources.packageOf(ScalaSources.currentProject(), dir);
        return packageName.isEmpty() ? "" : "package " + packageName + "\n\n";
    }
}
