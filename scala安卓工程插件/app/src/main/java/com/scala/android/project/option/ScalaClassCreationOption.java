package com.scala.android.project.option;

import com.rapid.api.project.structure.PSCheckedNewChildObject;
import com.rapid.api.project.structure.extension.PSObjectCreationForm;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;

import java.io.File;

/**
 * 「新建Scala类」：按输入的名字生成 {@code class Xxx} 骨架（带 package 头）。
 *
 * <p>刻意<b>不加</b> main 方法：否则每新建一个类都会多出一个入口候选，
 * 运行时还要弹窗让用户选入口。
 */
public class ScalaClassCreationOption extends ScalaCreationOption {

    @Override
    public String getName() {
        return "新建Scala类";
    }

    @Override
    public Form getForm() {
        Form form = new Form();
        FormInput filename = PSObjectCreationForm.createFileNameInput();
        filename.setValidator(PSObjectCreationForm.getIdentifierValidator());
        form.addInput(filename);
        return form;
    }

    @Override
    public void create(PSCheckedNewChildObject parent, Form form, OnCreatePSObjectListener listener) {
        File dir = parentDir(parent);
        String simpleName = fileName(form, null);
        if (simpleName == null) {
            listener.onFail("类名不能为空");
            return;
        }
        StringBuilder builder = new StringBuilder();
        if (dir != null) {
            builder.append(packageHeader(dir));
        }
        builder.append("class ").append(simpleName).append(":\n")
                .append("  def hello(): String = \"Hello from ").append(simpleName).append("\"\n");
        createTextFile(parent, form, ".scala", builder.toString(), listener);
    }
}
